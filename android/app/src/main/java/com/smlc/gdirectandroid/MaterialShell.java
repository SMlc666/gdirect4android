package com.smlc.gdirectandroid;

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
        public ScrollView diagnostics;
        public Spinner network, scanPreset, ranking, preferredIp;
        public View refresh, scan, scanCancel, scanProgress;
        public TextView networkDetails, endpointSummary, scanResult;
    }

    private final AppCompatActivity activity;
    private final UserPrefs prefs;
    private final Slots slots;
    private final FrameLayout content;
    private final BottomNavigationView navigation;
    private final View[] pages = new View[4];
    private LinearLayout history;
    private TextView currentName, currentDetail, currentState;
    private LinearProgressIndicator progress;
    private TextView footnote;
    private int currentPage = -1;
    private String lastHistoryKey = "";
    private String lastTransferSignature = "";

    private int primary, onSurface, surface, onSurfaceVariant, secondaryContainer;
    public MaterialShell(AppCompatActivity activity, UserPrefs prefs, Slots slots) {
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
        bar.setTitle("gdirect");
        bar.setSubtitle("Google Drive · Direct");
        bar.setTitleTextAppearance(activity, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        bar.setBackgroundColor(surface);
        bar.setContentInsetStartWithNavigation(dp(20));
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        shell.addView(bar, new LinearLayout.LayoutParams(-1, dp(66)));
        bar.getMenu().add("设置").setIcon(R.drawable.ic_settings)
            .setShowAsActionFlags(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
        bar.setOnMenuItemClickListener(item -> { open("settings"); return true; });

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
        navigation.setItemRippleColor(ColorStateList.valueOf(
            color(com.google.android.material.R.attr.colorPrimaryContainer, secondaryContainer)));
        navigation.setOnItemSelectedListener(item -> {
            showIndex(index(item.getItemId()));
            return true;
        });
        shell.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        activity.setContentView(shell);
        activity.getWindow().setStatusBarColor(surface);
        activity.getWindow().setNavigationBarColor(surface);
        if (Build.VERSION.SDK_INT >= 26) {
            int flags = activity.getWindow().getDecorView().getSystemUiVisibility();
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            activity.getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        open(activity.getIntent() == null ? "files" :
            activity.getIntent().getStringExtra("open_tab"));
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
        heading(page,"下载","传输在前台服务中运行，切换页面后仍可查看进度。");
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
        MaterialButton cancel=action("取消当前下载");
        cancel.setIconResource(R.drawable.ic_close);
        cancel.setOnClickListener(v->{
            TransferService.Snapshot s=TransferService.snapshot();
            if(!"running".equals(s.status)&&!"saving".equals(s.status)) {
                notice("当前没有运行中的下载");
                return;
            }
            Intent command=new Intent(activity,TransferService.class)
                .setAction(TransferService.ACTION_CANCEL);
            activity.startService(command);
            haptic();
        });
        box.addView(cancel);

        MaterialCardView historyCard=card(page);
        LinearLayout container=cardContent(historyCard);
        label(container,"最近任务","最近 30 条完成、取消或失败的记录，仅保存在本机。");
        history=stack();
        container.addView(history);
        MaterialButton clear=action("清空任务记录");
        clear.setOnClickListener(v->new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle("清空下载历史？")
            .setMessage("只删除本机历史记录，不删除已下载到手机的文件。")
            .setNegativeButton("返回",null)
            .setPositiveButton("清空",(dialog,which)->{
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
        LinearLayout buttons=new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        optimizeButtons(buttons,slots.scanProgress,slots.scanCancel);
        optimized.addView(buttons);
        space(optimized,10);
        label(optimized,"优先使用","可以手动选择刚刚验证过的 Google 前端 IP。");
        move(optimized,slots.preferredIp);
        move(optimized,slots.endpointSummary);

        MaterialCardView report=card(page);
        LinearLayout output=cardContent(report);
        label(output,"线路状态","扫描结果与失败详情");
        move(output,slots.scanResult);
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
                prefs.theme(types[i]);
                AppCompatDelegate.setDefaultNightMode(i==1
                    ?AppCompatDelegate.MODE_NIGHT_NO:i==2
                    ?AppCompatDelegate.MODE_NIGHT_YES:AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                return;
            }
        });
        MaterialSwitch dynamic=switchItem(group,"动态配色","Android 12+ 从壁纸提取 Material You 色彩",prefs.dynamicColor());
        dynamic.setOnCheckedChangeListener((button,checked)->{
            if(prefs.dynamicColor()!=checked){prefs.dynamicColor(checked);activity.recreate();}
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

        MaterialCardView advanced=card(page);
        LinearLayout body=cardContent(advanced);
        label(body,"高级诊断","TCP、TLS、IP 连接测试，以及原始运行日志。");
        TextView caution=text("默认隐藏。仅在排查网络和原生引擎问题时使用。",13,false);
        caution.setTextColor(onSurfaceVariant);
        body.addView(caution);
        MaterialButton toggle=action("展开工程诊断");
        body.addView(toggle);
        detach(slots.diagnostics);
        slots.diagnostics.setVisibility(View.GONE);
        body.addView(slots.diagnostics,new LinearLayout.LayoutParams(-1,dp(540)));
        toggle.setOnClickListener(v->{
            boolean visible=slots.diagnostics.getVisibility()==View.VISIBLE;
            slots.diagnostics.setVisibility(visible?View.GONE:View.VISIBLE);
            toggle.setText(visible?"展开工程诊断":"收起工程诊断");
            haptic();
        });

        MaterialCardView about=card(page);
        LinearLayout version=cardContent(about);
        label(version,"关于 gdirect","v1.0.0 · Android ARM64");
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
    private void showIndex(int next) {
        if(next==currentPage)return;
        final int previous=currentPage;
        currentPage=next;
        if(previous>=0) pages[previous].setVisibility(View.GONE);
        View incoming=pages[next];
        incoming.setVisibility(View.VISIBLE);
        incoming.bringToFront();
        if(prefs.animations()){
            incoming.setAlpha(0f);
            incoming.setTranslationY(dp(12));
            incoming.animate().alpha(1f).translationY(0f).setDuration(240).start();
        }else{
            incoming.setAlpha(1f);
            incoming.setTranslationY(0f);
        }
        if(next==1)refreshTransfers();
        haptic();
    }
    public void open(String target) {
        int id="transfers".equals(target)?TRANSFERS:"routes".equals(target)?ROUTES:
            "settings".equals(target)?SETTINGS:FILES;
        navigation.setSelectedItemId(id);
    }
    public void refreshTransfers() {
        if(currentName==null||history==null)return;
        TransferService.Snapshot s=TransferService.snapshot();
        String signature=s.id+"/"+s.status+"/"+s.done+"/"+s.total+"/"+s.detail;
        if(!signature.equals(lastTransferSignature)){
            lastTransferSignature=signature;
            boolean active="running".equals(s.status)||"saving".equals(s.status)
                ||"cancelling".equals(s.status);
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
        JSONArray log=TransferService.history(activity);
        String key=log.length()+":"+s.status+":"+s.id+":"+s.changedAt;
        if(key.equals(lastHistoryKey))return;
        lastHistoryKey=key;
        history.removeAllViews();
        if(log.length()==0){
            TextView hint=text("开始下载后，文件记录会出现在这里。",13,false);
            hint.setTextColor(onSurfaceVariant);
            history.addView(hint);
            return;
        }
        for(int i=0;i<Math.min(30,log.length());i++){
            JSONObject item=log.optJSONObject(i);
            if(item==null)continue;
            LinearLayout entry=stack();
            entry.setPadding(0,dp(12),0,dp(12));
            TextView name=text(item.optString("name","未命名"),14,true);
            name.setMaxLines(2);
            entry.addView(name);
            String status=item.optString("status","");
            String summary=("completed".equals(status)?"✓ 完成":
                "failed".equals(status)?"! 失败":"cancelled".equals(status)?"已取消":status);
            long timestamp=item.optLong("timestamp",0);
            String date=new SimpleDateFormat("MM-dd HH:mm",Locale.getDefault())
                .format(new Date(timestamp));
            TextView sub=text(summary+" · "+date+" · "+
                item.optString("detail",""),12,false);
            sub.setTextColor(onSurfaceVariant);
            sub.setMaxLines(3);
            entry.addView(sub);
            history.addView(entry);
            View separator=new View(activity);
            separator.setBackgroundColor(color(
                com.google.android.material.R.attr.colorOutlineVariant,0xffdddddd));
            history.addView(separator,new LinearLayout.LayoutParams(-1,dp(1)));
        }
    }
    public void notice(String message){
        Snackbar.make(content,message,Snackbar.LENGTH_LONG).show();
    }
    private void haptic(){
        if(!prefs.haptics())return;
        content.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
    }
}
