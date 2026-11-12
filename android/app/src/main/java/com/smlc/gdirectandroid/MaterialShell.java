package com.smlc.gdirectandroid;

import android.animation.ValueAnimator;
import android.os.Bundle;
import android.view.animation.PathInterpolator;
import androidx.transition.TransitionManager;
import com.google.android.material.transition.MaterialFadeThrough;
import com.google.android.material.shape.ShapeAppearanceModel;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Menu;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Material 3 product shell. The existing tested Rust/JNI + Drive workflows
 * are reparented into distinct user-focused destinations, not rewritten.
 */
public final class MaterialShell {
    private static final int FILES = 101, TRANSFERS = 102, ROUTES = 103, SETTINGS = 104;
    public static final class Slots {
        public DrivePanel drive;
        public Spinner network, scanPreset, ranking, preferredIp;
        public View googleDns, googleTls, manualIpInput, manualTcp,
            manualTls, publicTransfer, publicSave, downloadStatus,
            downloadCancel;
        public TextView networkDetails, endpointSummary, scanResult;

        public View refresh, scan, scanCancel, scanProgress;
    }

    private final AppCompatActivity activity;
    private final UserPrefs prefs;
    private final Slots slots;
    private final FrameLayout content;
    private final BottomNavigationView navigation;
    private final MaterialToolbar toolbar;
    private final View[] pages = new View[4];
    private TextView diagnosticsResult;
    private LinearLayout history;
    private TextView currentName, currentDetail, currentState;
    private LinearProgressIndicator progress;
    private LinearProgressIndicator scanProgressBar;
    private TextView scanProgressLabel;
    private TextView footnote;
    private int currentPage = -1;
    private String lastHistoryKey = "";
    private static final int TRANSFER_PAGE_SIZE = 40;
    private int visibleTransferCount = TRANSFER_PAGE_SIZE;
    private String lastTransferSignature = "";

    private int primary, onSurface, surface, onSurfaceVariant, secondaryContainer;
    public MaterialShell(AppCompatActivity activity, UserPrefs prefs, Slots slots, Bundle restored) {
        this.activity = activity;
        this.prefs = prefs;
        this.slots = slots;
        primary = color(androidx.appcompat.R.attr.colorPrimary, 0xff5365bc);
        onSurface = color(com.google.android.material.R.attr.colorOnSurface, 0xff1b1b21);
        surface = color(com.google.android.material.R.attr.colorSurface, Color.WHITE);
        onSurfaceVariant = color(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xff555660);
        secondaryContainer = color(com.google.android.material.R.attr.colorSecondaryContainer, 0xffe0e2f9);

        LinearLayout shell = new LinearLayout(activity);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainerLow, surface));

        MaterialToolbar bar = new MaterialToolbar(activity);
        toolbar = bar;
        bar.setTitle("gdirect");
        bar.setSubtitle("Google Drive · Direct");
        bar.setTitleTextAppearance(activity, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        bar.setBackgroundColor(surface);
        bar.setContentInsetStartWithNavigation(dp(20));
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        shell.addView(bar, new LinearLayout.LayoutParams(-1, dp(66)));
        // Settings belongs to exactly one place: the bottom destination.
        // No duplicate toolbar gear icon or additional settings navigation.

        content = new FrameLayout(activity);
        shell.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        pages[0] = filesPage();
        pages[1] = transfersPage();
        pages[2] = routesPage();
        pages[3] = settingsPage();
        for (View page : pages) {
            page.setVisibility(View.GONE);
            content.addView(page, new FrameLayout.LayoutParams(-1,-1));
        }

        navigation = new BottomNavigationView(activity);
        navigation.setLabelVisibilityMode(BottomNavigationView.LABEL_VISIBILITY_LABELED);
        navigation.getMenu().add(Menu.NONE, FILES, 0, "文件")
            .setIcon(R.drawable.ic_folder);
        navigation.getMenu().add(Menu.NONE, TRANSFERS, 1, "下载")
            .setIcon(R.drawable.ic_download);
        navigation.getMenu().add(Menu.NONE, ROUTES, 2, "线路")
            .setIcon(R.drawable.ic_route);
        navigation.getMenu().add(Menu.NONE, SETTINGS, 3, "设置")
            .setIcon(R.drawable.ic_settings);
        navigation.setBackgroundColor(surface);
        // The Material 3 pill is the selection feedback. The old large,
        // saturated full-cell ripple looked like an opaque flash.
        navigation.setItemActiveIndicatorEnabled(true);
        navigation.setItemActiveIndicatorWidth(dp(64));
        navigation.setItemActiveIndicatorHeight(dp(32));
        navigation.setItemActiveIndicatorMarginHorizontal(dp(8));
        navigation.setItemActiveIndicatorShapeAppearance(
            ShapeAppearanceModel.builder().setAllCornerSizes(dp(18)).build());
        navigation.setItemActiveIndicatorColor(ColorStateList.valueOf(secondaryContainer));
        int subtleRipple = Color.argb(22, Color.red(primary),
            Color.green(primary), Color.blue(primary));
        navigation.setItemRippleColor(ColorStateList.valueOf(subtleRipple));
        ColorStateList navigationColors = new ColorStateList(
            new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
            new int[]{primary, onSurfaceVariant});
        navigation.setItemIconTintList(navigationColors);
        navigation.setItemTextColor(navigationColors);
        navigation.setItemIconSize(dp(24));
        navigation.setOnItemSelectedListener(item -> {
            showIndex(index(item.getItemId()));
            return true;
        });
        shell.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        boolean enteringNewTheme = prefs.consumeThemeMotionPending() && motionEnabled();
        if (enteringNewTheme) {
            shell.setAlpha(0f);
            shell.setScaleX(0.987f);
            shell.setScaleY(0.987f);
        }
        activity.setContentView(shell);
        if (enteringNewTheme) {
            shell.post(() -> shell.animate()
                .alpha(1f).scaleX(1f).scaleY(1f).setDuration(380L)
                .setInterpolator(new PathInterpolator(0.2f,0f,0f,1f))
                .start());
        }
        activity.getWindow().setStatusBarColor(surface);
        activity.getWindow().setNavigationBarColor(surface);
        if (Build.VERSION.SDK_INT >= 26) {
            int flags = activity.getWindow().getDecorView().getSystemUiVisibility();
            int mask = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR |
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags = Color.luminance(surface) > 0.50 ? flags | mask : flags & ~mask;
            activity.getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        // Prefer explicit notification intent, then restored instance state,
        // then last user-selected destination. Theme changes must not jump
        // back to the file browser.
        String requested = activity.getIntent() == null ? null :
            activity.getIntent().getStringExtra("open_tab");
        if (requested == null && restored != null) {
            requested = restored.getString("ui.selected_tab");
        }
        if (requested == null) requested = prefs.currentTab();
        open(requested);
        for (int i=0; i<pages.length; i++) {
            if (pages[i] instanceof ScrollView) {
                final int index=i;
                final ScrollView scroll = (ScrollView) pages[i];
                int offset = restored == null ? prefs.scroll(i) :
                    restored.getInt("ui.scroll_" + i, prefs.scroll(i));
                if (offset > 0) scroll.post(() -> scroll.scrollTo(0,offset));
            }
        }
    }

    private int color(int attr, int fallback) {
        return MaterialColors.getColor(activity, attr, fallback);
    }
    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
    private static void detach(View view) {
        if (view == null) return;
        if (view.getParent() instanceof ViewGroup) ((ViewGroup)view.getParent()).removeView(view);
    }
    private ScrollView screen(LinearLayout page) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(dp(18), dp(12), dp(18), dp(24));
        scroll.addView(page);
        return scroll;
    }
    private LinearLayout stack() {
        LinearLayout l = new LinearLayout(activity);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }
    private TextView text(String label, int sp, boolean bold) {
        TextView t = new TextView(activity);
        t.setText(label);
        t.setTextSize(sp);
        t.setTextColor(onSurface);
        t.setIncludeFontPadding(true);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private void space(LinearLayout root, int dp) {
        View v = new View(activity);
        root.addView(v, new LinearLayout.LayoutParams(1, dp(dp)));
    }
    private MaterialCardView card(LinearLayout holder) {
        MaterialCardView card = new MaterialCardView(activity);
        card.setRadius(dp(24));
        card.setCardElevation(0);
        card.setUseCompatPadding(false);
        card.setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainer, surface));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,-2);
        params.bottomMargin = dp(14);
        holder.addView(card,params);
        return card;
    }
    private LinearLayout cardContent(MaterialCardView card) {
        LinearLayout inner = stack();
        inner.setPadding(dp(19),dp(19),dp(19),dp(19));
        card.addView(inner);
        return inner;
    }
    private void heading(LinearLayout root, String label, String description) {
        TextView h = text(label, 26, true);
        root.addView(h);
        if (description != null && !description.isEmpty()) {
            TextView d = text(description, 13, false);
            d.setTextColor(onSurfaceVariant);
            d.setPadding(0,dp(5),0,dp(12));
            root.addView(d);
        } else space(root,12);
    }
    private void label(LinearLayout root,String title,String detail) {
        TextView t = text(title,17,true);
        root.addView(t);
        if(detail!=null){
            TextView d=text(detail,13,false);
            d.setTextColor(onSurfaceVariant);
            d.setPadding(0,dp(3),0,dp(8));
            root.addView(d);
        }
    }
    private MaterialButton action(String title) {
        MaterialButton btn = new MaterialButton(activity);
        btn.setText(title);
        btn.setAllCaps(false);
        btn.setCornerRadius(dp(18));
        btn.setMinHeight(dp(52));
        return btn;
    }
    private void move(LinearLayout target, View view) {
        if(view == null) return;
        detach(view);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.bottomMargin=dp(8);
        target.addView(view,p);
    }

    private View filesPage() {
        LinearLayout page = stack();
        MaterialCardView hero = card(page);
        hero.setCardBackgroundColor(secondaryContainer);
        LinearLayout intro = cardContent(hero);
        TextView eyebrow = text("你的云端空间",13,true);
        eyebrow.setTextColor(onSurfaceVariant);
        intro.addView(eyebrow);
        TextView title = text("文件，无界直达。",27,true);
        intro.addView(title);
        TextView detail = text("浏览 Google Drive，使用优选线路保存至手机。",14,false);
        detail.setTextColor(onSurfaceVariant);
        detail.setPadding(0,dp(6),0,0);
        intro.addView(detail);
        MaterialCardView contentCard = card(page);
        LinearLayout cloud = cardContent(contentCard);
        if(slots.drive!=null)move(cloud,slots.drive);
        return screen(page);
    }

    private View transfersPage() {
        LinearLayout page=stack();
        heading(page,"下载队列","多文件任务、暂停续传与失败恢复；重启应用也会保留任务。");
        MaterialCardView card=card(page);
        LinearLayout box=cardContent(card);
        currentState=text("准备就绪",13,true);
        currentState.setTextColor(primary);
        box.addView(currentState);
        space(box,5);
        currentName=text("暂无正在进行的任务",20,true);
        box.addView(currentName);
        space(box,7);
        currentDetail=text("选择云盘中的文件，开始一次下载。",13,false);
        currentDetail.setTextColor(onSurfaceVariant);
        box.addView(currentDetail);
        space(box,14);
        progress=new LinearProgressIndicator(activity);
        progress.setTrackCornerRadius(dp(4));
        progress.setIndeterminate(false);
        box.addView(progress,new LinearLayout.LayoutParams(-1,dp(5)));
        space(box,17);
        MaterialButton pause=action("暂停当前下载");
        pause.setIconResource(android.R.drawable.ic_media_pause);
        pause.setOnClickListener(v->{
            TransferService.Snapshot running=TransferService.snapshot();
            if(running.id.isEmpty() ||
                !("running".equals(running.status)||"saving".equals(running.status))){
                notice("当前没有正在运行的任务"); return;
            }
            ((MainActivity)activity).queueCommand(TransferService.COMMAND_PAUSE,running.id);
            haptic();
        });
        box.addView(pause);

        MaterialCardView tasksCard=card(page);
        LinearLayout container=cardContent(tasksCard);
        label(container,"全部下载任务","任务存储于手机本机；可排队、暂停、恢复、删除。");
        MaterialButton resumeAll=action("继续所有暂停或失败的任务");
        resumeAll.setOnClickListener(v->{
            ((MainActivity)activity).queueCommand(TransferService.COMMAND_RESUME_ALL,null);
            haptic();
        });
        container.addView(resumeAll);
        history=stack();
        container.addView(history);
        MaterialButton clear=action("清理已完成与已取消的记录");
        clear.setOnClickListener(v->new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle("清理已结束的任务记录？")
            .setMessage("只清理已完成与已取消的本机任务记录；不会删除下载好的文件或暂停中的断点。")
            .setNegativeButton("返回",null)
            .setPositiveButton("清理",(dialog,which)->{
                TransferService.clearHistory(activity);
                lastHistoryKey="";
                refreshTransfers();
            }).show());
        container.addView(clear);
        refreshTransfers();
        return screen(page);
    }

    private View routesPage() {
        LinearLayout page=stack();
        heading(page,"智能线路","选择 Wi‑Fi、移动数据或 VPN；系统不会悄悄更换所选路径。");
        MaterialCardView selector=card(page);
        LinearLayout network=cardContent(selector);
        label(network,"当前使用的网络","Drive API 和 libaria2 文件传输共用此网络。");
        move(network,slots.network);
        move(network,slots.refresh);

        MaterialCardView optimize=card(page);
        LinearLayout optimized=cardContent(optimize);
        label(optimized,"Google IP 智能优选","通过 TLS、Google Drive API 与短文件测速验证节点。");
        move(optimized,slots.scanPreset);
        move(optimized,slots.ranking);
        move(optimized,slots.scan);
        scanProgressBar = new LinearProgressIndicator(activity);
        scanProgressBar.setTrackCornerRadius(dp(4));
        scanProgressBar.setVisibility(View.GONE);
        optimized.addView(scanProgressBar,new LinearLayout.LayoutParams(-1,dp(5)));
        scanProgressLabel = text("",12,false);
        scanProgressLabel.setTextColor(onSurfaceVariant);
        scanProgressLabel.setVisibility(View.GONE);
        optimized.addView(scanProgressLabel);
        LinearLayout buttons=new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        optimizeButtons(buttons,slots.scanProgress,slots.scanCancel);
        optimized.addView(buttons);
        space(optimized,10);
        label(optimized,"优先使用","可以手动选择刚刚验证过的 Google 前端 IP。");
        move(optimized,slots.preferredIp);
        move(optimized,slots.endpointSummary);
        if (slots.endpointSummary != null)
            slots.endpointSummary.setTextColor(onSurfaceVariant);

        MaterialCardView report=card(page);
        LinearLayout output=cardContent(report);
        label(output,"线路状态","扫描结果与失败详情");
        move(output,slots.scanResult);
        if (slots.scanResult != null) slots.scanResult.setTextColor(onSurface);
        return screen(page);
    }

    private void optimizeButtons(LinearLayout row, View one, View two) {
        for(View v:new View[]{one,two}) {
            if(v==null)continue;
            detach(v);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);
            p.rightMargin=dp(4);
            row.addView(v,p);
        }
    }

    private View settingsPage() {
        LinearLayout page=stack();
        heading(page,"个性化","色彩、动画、扫描、并发与高级网络选项都由你决定。");

        MaterialCardView appearance=card(page);
        LinearLayout group=cardContent(appearance);
        label(group,"外观","Material 3 · 跟随系统或自由切换主题");
        MaterialButtonToggleGroup themes=new MaterialButtonToggleGroup(activity);
        themes.setSingleSelection(true);
        themes.setSelectionRequired(true);
        final String[] types={"system","light","dark"};
        final int[] themeIds={View.generateViewId(),View.generateViewId(),View.generateViewId()};
        final String[] themeNames={"自动","浅色","深色"};
        for(int i=0;i<types.length;i++){
            MaterialButton option=action(themeNames[i]);
            option.setId(themeIds[i]);
            option.setCheckable(true);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1);
            themes.addView(option,p);
        }
        group.addView(themes);
        String selected=prefs.theme();
        int chosen=0;
        for(int i=0;i<types.length;i++)if(types[i].equals(selected))chosen=i;
        themes.check(themeIds[chosen]);
        themes.addOnButtonCheckedListener((view,id,checked)->{
            if(!checked)return;
            for(int i=0;i<types.length;i++)if(themeIds[i]==id&&!prefs.theme().equals(types[i])){
                final String newMode=types[i];
                // Only one Activity recreation, with a coordinated outgoing
                // theme veil and incoming fade-through. MainActivity applies
                // AppCompat night mode BEFORE super.onCreate.
                animateThemeChange(() -> {
                    prefs.theme(newMode);
                    activity.recreate();
                });
                return;
            }
        });
        MaterialSwitch dynamic=switchItem(group,"动态配色","Android 12+ 从壁纸提取 Material You 色彩",prefs.dynamicColor());
        dynamic.setOnCheckedChangeListener((button,checked)->{
            if (prefs.dynamicColor()!=checked) {
                animateThemeChange(() -> {
                    prefs.dynamicColor(checked);
                    activity.recreate();
                });
            }
        });
        MaterialSwitch motion=switchItem(group,"精致动效","页面转场与内容进入动画",prefs.animations());
        motion.setOnCheckedChangeListener((button,checked)->prefs.animations(checked));
        MaterialSwitch haptic=switchItem(group,"轻触反馈","重要操作触发轻微触觉反馈",prefs.haptics());
        haptic.setOnCheckedChangeListener((button,checked)->prefs.haptics(checked));
        MaterialSwitch followDownloads=switchItem(group,"开始下载后打开任务页",
            "可以关闭，保持停留在云盘文件列表",prefs.startOnDownloads());
        followDownloads.setOnCheckedChangeListener((button,checked)->
            prefs.startOnDownloads(checked));

        MaterialCardView performance=card(page);
        LinearLayout perf=cardContent(performance);
        label(perf,"性能与扫描","适用于长文件下载、蜂窝流量与节点搜索");
        slider(perf,"扫描候选数量",32,768,32,prefs.scanSize(),n->prefs.scanSize(n));
        slider(perf,"扫描并发线程",4,64,4,prefs.scanThreads(),n->prefs.scanThreads(n));
        slider(perf,"libaria2 下载连接数",1,16,1,prefs.connections(),n->prefs.connections(n));
        slider(perf,"优选缓存时间（分钟）",5,240,5,prefs.cacheMinutes(),n->prefs.cacheMinutes(n));
        slider(perf,"备用节点数量",1,8,1,prefs.fallbackCount(),n->prefs.fallbackCount(n));

        MaterialCardView portability=card(page);
        LinearLayout configuration=cardContent(portability);
        label(configuration,"配置备份与迁移","用户设置可以导出、导入，不包含 OAuth Token、密钥和个人云盘文件。");
        TextView safety=text("导出的 JSON 只包含外观、动画、扫描与下载参数。"
            +" 不会导出 OAuth 客户端 JSON、刷新令牌或临时网络句柄。",13,false);
        safety.setTextColor(onSurfaceVariant);
        configuration.addView(safety);
        space(configuration,10);
        MaterialButton export=action("导出设置 JSON");
        export.setIconResource(R.drawable.ic_download);
        export.setOnClickListener(v->((MainActivity)activity).exportConfiguration());
        configuration.addView(export);
        MaterialButton importButton=action("从 JSON 导入设置");
        importButton.setOnClickListener(v->((MainActivity)activity).importConfiguration());
        configuration.addView(importButton);

        MaterialCardView advanced=card(page);
        LinearLayout body=cardContent(advanced);
        label(body,"高级诊断","仅在排查连接、TLS 或原生引擎问题时展开。");
        TextView caution=text("这些测试会绑定线路页选择的 Android 网络，不会自动切换到 VPN。",13,false);
        caution.setTextColor(onSurfaceVariant);
        body.addView(caution);
        space(body,8);
        MaterialButton toggle=action("展开工程诊断");
        toggle.setIconResource(R.drawable.ic_route);
        body.addView(toggle);

        // Crucial fix: never embed the old full-height ScrollView inside the
        // Settings ScrollView. Move ONLY the live diagnostic controls into
        // the Settings page's ONE scrollable column. No fixed 540dp viewport,
        // no nested touch interception, and no stale Milestone headings.
        LinearLayout diagnosis=stack();
        diagnosis.setVisibility(View.GONE);
        body.addView(diagnosis, new LinearLayout.LayoutParams(-1,-2));
        space(diagnosis,12);
        label(diagnosis,"网络详情","查看接口、网络标识与系统 DNS");
        move(diagnosis,slots.networkDetails);
        label(diagnosis,"连接验证","从所选网络执行 TCP、TLS 和 HTTPS 验证");
        move(diagnosis,slots.googleDns);
        move(diagnosis,slots.googleTls);
        label(diagnosis,"指定 IP","确认某个 Google 前端地址是否可达");
        move(diagnosis,slots.manualIpInput);
        move(diagnosis,slots.manualTcp);
        move(diagnosis,slots.manualTls);
        label(diagnosis,"公开测试","只测试公开 Google Drive API 文档");
        move(diagnosis,slots.publicTransfer);
        move(diagnosis,slots.publicSave);
        label(diagnosis,"原生下载器","状态查看与紧急取消");
        move(diagnosis,slots.downloadStatus);
        move(diagnosis,slots.downloadCancel);
        label(diagnosis,"运行结果","日志会在此处显示，可长按复制");
        diagnosticsResult=text("点击上方某项测试查看结果。",13,false);
        diagnosticsResult.setTextColor(onSurfaceVariant);
        diagnosticsResult.setTextIsSelectable(true);
        diagnosticsResult.setPadding(dp(8),dp(10),dp(8),dp(10));
        diagnosis.addView(diagnosticsResult,new LinearLayout.LayoutParams(-1,-2));
        toggle.setOnClickListener(v->{
            boolean opening=diagnosis.getVisibility()!=View.VISIBLE;
            if(motionEnabled()) {
                MaterialFadeThrough transition = new MaterialFadeThrough();
                transition.setDuration(250L);
                TransitionManager.beginDelayedTransition(body,transition);
            }
            diagnosis.setVisibility(opening?View.VISIBLE:View.GONE);
            toggle.setText(opening?"收起工程诊断":"展开工程诊断");
            toggle.setIconResource(opening?R.drawable.ic_close:R.drawable.ic_route);
            haptic();
        });

        MaterialCardView about=card(page);
        LinearLayout version=cardContent(about);
        label(version,"关于 gdirect","v1.0.0 RC3 · Android ARM64");
        footnote=text("使用原版 libaria2、Google Drive API 与 Google 官方 TLS 身份验证。"
            +" 浏览器 OAuth 和 Token 刷新使用系统默认网络；文件 API 与下载遵照所选 Android 网络。",13,false);
        footnote.setTextColor(onSurfaceVariant);
        version.addView(footnote);
        return screen(page);
    }

    private MaterialSwitch switchItem(LinearLayout root,String title,String note,boolean checked){
        LinearLayout line=new LinearLayout(activity);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0,dp(13),0,dp(13));
        LinearLayout labels=stack();
        labels.addView(text(title,15,true));
        TextView desc=text(note,12,false);
        desc.setTextColor(onSurfaceVariant);
        labels.addView(desc);
        line.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        MaterialSwitch result=new MaterialSwitch(activity);
        result.setChecked(checked);
        line.addView(result);
        root.addView(line);
        return result;
    }

    private interface IntChange { void set(int value); }
    private void slider(LinearLayout parent,String name,int min,int max,int step,int initial,IntChange callback){
        TextView title=text(name+" · "+initial,14,true);
        title.setPadding(0,dp(14),0,0);
        parent.addView(title);
        Slider slider=new Slider(activity);
        slider.setValueFrom(min);
        slider.setValueTo(max);
        slider.setStepSize(step);
        int corrected=Math.max(min,Math.min(max,
            min+Math.round((initial-min)/(float)step)*step));
        slider.setValue(corrected);
        parent.addView(slider);
        slider.addOnChangeListener((s,value,fromUser)->{
            if(!fromUser)return;
            int actual=Math.round(value);
            title.setText(name+" · "+actual);
            callback.set(actual);
        });
    }

    private int index(int id) {
        return id==TRANSFERS?1:id==ROUTES?2:id==SETTINGS?3:0;
    }
    private int destination(int index) {
        return index==1?TRANSFERS:index==2?ROUTES:index==3?SETTINGS:FILES;
    }
    private String tabName(int index) {
        return index==1?"transfers":index==2?"routes":index==3?"settings":"files";
    }
    private boolean motionEnabled() {
        // Respect both the user's choice AND Android accessibility/animator
        // scale 0. No unwanted motion when "Remove animations" is enabled.
        return prefs.animations() && ValueAnimator.areAnimatorsEnabled();
    }

    public void saveUiState(Bundle state) {
        if (state == null) return;
        state.putString("ui.selected_tab", tabName(Math.max(0,currentPage)));
        retainScroll();
        for (int i=0;i<pages.length;i++) {
            if(pages[i] instanceof ScrollView) {
                state.putInt("ui.scroll_"+i,((ScrollView)pages[i]).getScrollY());
            }
        }
    }

    private void retainScroll() {
        for(int i=0;i<pages.length;i++) {
            if (pages[i] instanceof ScrollView) {
                prefs.retainScroll(i,((ScrollView)pages[i]).getScrollY());
            }
        }
    }

    /**
     * Two-part theme motion: the old surface fades to a Material tonal veil,
     * then a new-configuration Activity fades/settles into place. It avoids
     * storing screenshots of potentially sensitive Drive/OAuth content.
     */
    private void animateThemeChange(Runnable applyTheme) {
        retainScroll();
        prefs.currentTab(tabName(Math.max(0,currentPage)));
        if (!motionEnabled()) {
            prefs.themeMotionPending(false);
            applyTheme.run();
            return;
        }
        FrameLayout host = activity.findViewById(android.R.id.content);
        if (host == null) { applyTheme.run(); return; }
        View veil=new View(activity);
        veil.setBackgroundColor(color(
            com.google.android.material.R.attr.colorSurfaceContainerHigh,secondaryContainer));
        veil.setAlpha(0f);
        veil.setClickable(true);
        host.addView(veil,new FrameLayout.LayoutParams(-1,-1));
        veil.animate().alpha(1f).setDuration(180L)
            .setInterpolator(new PathInterpolator(0.2f,0f,0f,1f))
            .withEndAction(() -> {
                prefs.themeMotionPending(true);
                applyTheme.run();
            }).start();
    }

    private void showIndex(int next) {
        if (next == currentPage) return;
        int previous = currentPage;
        currentPage = next;
        prefs.currentTab(tabName(next));
        toolbar.setSubtitle(next==0?"云端文件":next==1?"传输任务":
            next==2?"智能线路":"个性化与高级选项");

        // Material's Fade Through is intended for *peer* destinations in
        // bottom navigation. Outgoing and incoming pages are animated in
        // one transition, never as independent, overlapping Y slides.
        TransitionManager.endTransitions(content);
        if (previous < 0 || !motionEnabled()) {
            for (int i=0;i<pages.length;i++) {
                pages[i].setVisibility(i==next?View.VISIBLE:View.GONE);
                pages[i].setAlpha(1f);
                pages[i].setTranslationY(0f);
            }
        } else {
            for(int i=0;i<pages.length;i++) {
                if(i!=previous && i!=next)pages[i].setVisibility(View.GONE);
            }
            MaterialFadeThrough fade = new MaterialFadeThrough();
            fade.setDuration(300L);
            fade.setInterpolator(new PathInterpolator(0.2f,0f,0f,1f));
            TransitionManager.beginDelayedTransition(content,fade);
            pages[previous].setVisibility(View.GONE);
            pages[next].setVisibility(View.VISIBLE);
        }
        if(next==1)refreshTransfers();
        if(previous>=0)haptic();
    }

    public void open(String target) {
        int id="transfers".equals(target)?TRANSFERS:"routes".equals(target)?ROUTES:
            "settings".equals(target)?SETTINGS:FILES;
        navigation.setSelectedItemId(id);
    }

    public void showDiagnosticResult(String text) {
        if (diagnosticsResult != null) {
            diagnosticsResult.setText(text);
        }
    }

    /** Live scan progress, without requiring a debug diagnostics button. */
    public void refreshScan() {
        if (scanProgressBar == null || scanProgressLabel == null) return;
        try {
            JSONObject state = new JSONObject(MainActivity.nativeGoogleScanProgress());
            boolean running = state.optBoolean("running",false);
            int checked = state.optInt("checked",0);
            int good = state.optInt("good",0);
            int total = state.optInt("total",0);
            scanProgressBar.setVisibility(running ? View.VISIBLE : View.GONE);
            scanProgressLabel.setVisibility(running ? View.VISIBLE : View.GONE);
            if (running) {
                scanProgressBar.setProgressCompat(
                    total <= 0 ? 0 : Math.max(0,Math.min(100,checked*100/total)),
                    prefs.animations());
                scanProgressLabel.setText("已验证 "+checked+" / "+total
                    +" · 可用 "+good+" 个 · 正在优选 Google 前端…");
            }
        } catch (LinkageError | Exception ignored) {
            scanProgressBar.setVisibility(View.GONE);
            scanProgressLabel.setVisibility(View.GONE);
        }
    }

    public void refreshTransfers() {
        if(currentName==null||history==null)return;
        TransferService.Snapshot s=TransferService.snapshot();
        String signature=s.id+"/"+s.status+"/"+s.done+"/"+s.total+"/"+s.detail;
        if(!signature.equals(lastTransferSignature)){
            lastTransferSignature=signature;
            boolean active="running".equals(s.status)||"saving".equals(s.status)
                ||"cancelling".equals(s.status);
            if (navigation != null) {
                if (active) navigation.getOrCreateBadge(TRANSFERS).setVisible(true);
                else navigation.removeBadge(TRANSFERS);
            }
            currentState.setText(active?"进行中":"completed".equals(s.status)?"已完成":
                "failed".equals(s.status)?"失败":"cancelled".equals(s.status)?"已取消":"准备就绪");
            currentName.setText(s.name.isEmpty()?"暂无正在进行的任务":s.name);
            currentDetail.setText(s.detail);
            int percentage=s.percent();
            progress.setIndeterminate(active&&percentage<0);
            if(percentage>=0){
                progress.setIndeterminate(false);
                progress.setProgressCompat(percentage,prefs.animations());
            }else if(!active){
                progress.setIndeterminate(false);
                progress.setProgressCompat(0,false);
            }
        }
        // The active summary badge is global; avoid opening SQLite and
        // rebuilding 100 file cards while viewing another destination.
        if (currentPage!=1 && currentPage!=-1) return;
        JSONArray queued=TransferService.tasks(activity);
        // Avoid tearing down the entire list every time the active byte
        // counter changes. Live progress stays in the summary header above.
        StringBuilder fingerprint=new StringBuilder();
        for(int i=0;i<queued.length();i++){
            JSONObject job=queued.optJSONObject(i);
            if(job!=null)fingerprint.append(job.optString("id"))
                .append(':').append(job.optString("state")).append(';');
        }
        String key=fingerprint.toString();
        if(key.equals(lastHistoryKey))return;
        lastHistoryKey=key;
        history.removeAllViews();
        if(queued.length()==0){
            TextView hint=text("还没有下载任务。打开「文件」选择多个文件，任务会自动排队。",14,false);
            hint.setTextColor(onSurfaceVariant);
            hint.setPadding(0,dp(12),0,dp(18));
            history.addView(hint);
            return;
        }
        for(int i=0;i<Math.min(visibleTransferCount,queued.length());i++){
            JSONObject job=queued.optJSONObject(i);
            if(job==null)continue;
            final String id=job.optString("id");
            final String state=job.optString("state");
            MaterialCardView card=new MaterialCardView(activity);
            card.setRadius(dp(16)); card.setCardElevation(0);
            card.setCardBackgroundColor(color(
                com.google.android.material.R.attr.colorSurfaceContainerHigh,surface));
            LinearLayout inner=stack();
            inner.setPadding(dp(13),dp(14),dp(13),dp(12));
            TextView name=text(job.optString("name","未命名文件"),15,true);
            name.setMaxLines(2);inner.addView(name);
            String stateLabel="queued".equals(state)?"等待下载":
                "running".equals(state)?"下载中":
                "paused".equals(state)?"已暂停":
                "failed".equals(state)?"失败":
                "completed".equals(state)?"已完成":
                "cancelled".equals(state)?"已取消":state;
            TextView stateText=text(stateLabel+" · "+job.optString("route",""),12,false);
            stateText.setTextColor("failed".equals(state)?
                color(android.R.attr.colorError,primary):onSurfaceVariant);
            inner.addView(stateText);
            TextView details=text(job.optString("detail",""),12,false);
            details.setTextColor(onSurfaceVariant);
            details.setMaxLines(3);
            inner.addView(details);
            LinearLayout controls=new LinearLayout(activity);
            controls.setOrientation(LinearLayout.HORIZONTAL);
            controls.setPadding(0,dp(8),0,0);
            switch(state){
                case "queued":
                    taskButton(controls,"置顶",TransferService.COMMAND_PRIORITY,id);
                    taskButton(controls,"暂停",TransferService.COMMAND_PAUSE,id);
                    taskButton(controls,"取消",TransferService.COMMAND_CANCEL,id);
                    break;
                case "running":
                    taskButton(controls,"暂停",TransferService.COMMAND_PAUSE,id);
                    taskButton(controls,"取消",TransferService.COMMAND_CANCEL,id);
                    break;
                case "paused":
                case "failed":
                    taskButton(controls,"继续",TransferService.COMMAND_RESUME,id);
                    taskButton(controls,"取消",TransferService.COMMAND_CANCEL,id);
                    break;
                default:
                    taskButton(controls,"删除记录",TransferService.COMMAND_REMOVE,id);
                    break;
            }
            inner.addView(controls);
            card.addView(inner);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
            lp.topMargin=dp(9);
            history.addView(card,lp);
        }
        if(queued.length()>visibleTransferCount){
            MaterialButton more=action("加载更多任务 · 已显示 "
                +visibleTransferCount+" / "+queued.length());
            more.setOnClickListener(v->{
                visibleTransferCount=Math.min(visibleTransferCount+TRANSFER_PAGE_SIZE,
                    queued.length());
                lastHistoryKey="";
                refreshTransfers();
            });
            history.addView(more);
        }
    }
    private void taskButton(LinearLayout row,String text,String command,String id){
        MaterialButton b=action(text);
        b.setCornerRadius(dp(14));
        b.setMinHeight(dp(40));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);
        lp.rightMargin=dp(5);
        row.addView(b,lp);
        b.setOnClickListener(v->{
            ((MainActivity)activity).queueCommand(command,id);
            lastHistoryKey="";
            history.postDelayed(this::refreshTransfers,250);
        });
    }
    public void notice(String message){
        Snackbar.make(content,message,Snackbar.LENGTH_LONG).show();
    }
    private void haptic(){
        if(!prefs.haptics())return;
        content.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
    }
}
