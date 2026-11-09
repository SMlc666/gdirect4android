//! The Android NDK per-socket binding API, with a host-only test stub.
//! Selected physical-network failures must NEVER fall back to the VPN.
#[cfg(target_os = "android")]
use socket2::{Domain, Protocol, Socket, Type};
use std::io;
use std::net::{SocketAddr, TcpStream};
#[cfg(target_os = "android")]
use std::os::fd::AsRawFd;
use std::time::Duration;

#[cfg(target_os = "android")]
#[link(name = "android")]
unsafe extern "C" {
    fn android_setsocknetwork(network: u64, fd: libc::c_int) -> libc::c_int;
}

/// network_handle=0 follows the system path (potentially VPN).
/// Other handles are obtained from android.net.Network.getNetworkHandle().
pub fn connect(addr: SocketAddr, network_handle: u64, timeout: Duration) -> io::Result<TcpStream> {
    if network_handle == 0 {
        return crate::netif::connect_via(&addr, timeout, None);
    }
    #[cfg(target_os = "android")]
    {
        let socket = Socket::new(
            Domain::for_address(addr),
            Type::STREAM,
            Some(Protocol::TCP),
        )?;
        if unsafe { android_setsocknetwork(network_handle, socket.as_raw_fd()) } != 0 {
            let error = io::Error::last_os_error();
            return Err(io::Error::new(
                error.kind(),
                format!(
                    "Android 网络绑定被拒绝 (errno={}): {}。检查 VPN allowBypass / 系统 VPN lockdown",
                    error.raw_os_error().unwrap_or(-1),
                    error
                ),
            ));
        }
        socket.connect_timeout(&addr.into(), timeout)?;
        Ok(socket.into())
    }
    #[cfg(not(target_os = "android"))]
    {
        Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "非 Android 测试平台不支持 network handle 绑定",
        ))
    }
}
