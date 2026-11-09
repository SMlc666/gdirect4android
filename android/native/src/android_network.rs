//! The supported Android NDK per-socket binding API.
//! Never silently fall back from a user-selected physical network to VPN.
use socket2::{Domain, Protocol, Socket, Type};
use std::io;
use std::net::{SocketAddr, TcpStream};
use std::os::fd::AsRawFd;
use std::time::Duration;

#[link(name = "android")]
unsafe extern "C" {
    fn android_setsocknetwork(network: u64, fd: libc::c_int) -> libc::c_int;
}

/// network_handle=0 means system default; otherwise a handle obtained from
/// android.net.Network.getNetworkHandle(). Binds before connecting.
pub fn connect(addr: SocketAddr, network_handle: u64, timeout: Duration) -> io::Result<TcpStream> {
    if network_handle == 0 {
        return crate::netif::connect_via(&addr, timeout, None);
    }
    let socket = Socket::new(
        Domain::for_address(addr),
        Type::STREAM,
        Some(Protocol::TCP),
    )?;
    if unsafe { android_setsocknetwork(network_handle, socket.as_raw_fd()) } != 0 {
        let error = io::Error::last_os_error();
        return Err(io::Error::new(
            error.kind(),
            format!("Android 网络绑定被拒绝 (errno={}): {}。检查 VPN allowBypass / 系统 VPN lockdown",
                error.raw_os_error().unwrap_or(-1), error),
        ));
    }
    socket.connect_timeout(&addr.into(), timeout)?;
    Ok(socket.into())
}
