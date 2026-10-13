package io.github.voreulch.honoradvanced;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    private static final int ACCENT = 0xFF2563EB;
    private static final int ACCENT_DIM = 0xFF3B82F6;

    /** QQ group join link opened in a browser; the authKey is time-limited,
     *  swap the whole URL when it expires */
    private static final String QQ_JOIN_URL =
            "https://qun.qq.com/universal-share/share?ac=1"
            + "&authKey=A%2F%2BiHb%2BVpkrFqqI7Qcg5%2BwsvbXYVA7uQD96Uf4711yPds"
            + "%2FrhUu%2FqDyi4KrQboVXM"
            + "&busi_data=eyJncm91cENvZGUiOiIyOTc2NDkyMzAiLCJ0b2tlbiI6InMw"
            + "K2pqbmRpbnA2bDl5M0ppMXVBeitUdll6enAzUkc2dVpDQnh2RDdoaXNSNGF4"
            + "VlBMWjhRa3JvNS93bFVMeVoiLCJ1aW4iOiI0NDY4MyJ9"
            + "&data=h-QBqjNfq-nswTwFBF3Mk1rluWMYzC2NCoB1da80tL95oHZq0z4yVEul"
            + "GV45rm0ntVXbG4hbek3pB84TX2seGA&svctype=4&tempid=h5_group_info";

    private boolean dark;
    private int pad; // dp -> px

    private LinearLayout content;
    private ScrollView pageMine;
    private ScrollView pageGlass;
    private ScrollView pageInstaller;
    private ScrollView pageAbout;
    private TextView tabMineText, tabAboutText;
    private View navBar;

    private static class Binding {
        final String key;
        final boolean restart; // glass line needs a scope restart, installer line doesn't
        final boolean def;     // default switch state (compat mode defaults OFF)

        Binding(String key, boolean restart) {
            this(key, restart, true);
        }

        Binding(String key, boolean restart, boolean def) {
            this.key = key;
            this.restart = restart;
            this.def = def;
        }
    }

    // ---- switch keys live in shared_prefs/config.xml, read by hooks via the
    //      module's exported CfgProvider ----
    private static final Binding B_GLASS    = new Binding(Cfg.K_GLASS, true);
    private static final Binding B_SYSTEM   = new Binding(Cfg.K_SYSTEM, true);
    private static final Binding B_KEYGUARD = new Binding(Cfg.K_KEYGUARD, true);
    private static final Binding B_GALLERY  = new Binding(Cfg.K_GALLERY, true);
    private static final Binding B_PURIFY   = new Binding(Cfg.K_PURIFY, false);
    private static final Binding B_INFOCARD = new Binding(Cfg.K_INFOCARD, false);
    private static final Binding B_DOWNGRADE = new Binding(Cfg.K_DOWNGRADE, false);
    private static final Binding B_COMPAT   = new Binding(Cfg.K_COMPAT, false, false);

    private boolean prefsOn(String key) {
        return prefsOn(key, true);
    }

    private boolean prefsOn(String key, boolean def) {
        return getSharedPreferences(Cfg.PREFS_FILE, MODE_PRIVATE).getBoolean(key, def);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        pad = (int) (getResources().getDisplayMetrics().density + 0.5f);

        android.content.SharedPreferences p =
                getSharedPreferences(Cfg.PREFS_FILE, MODE_PRIVATE);
        android.content.SharedPreferences.Editor ed = p.edit();
        boolean dirty = false;
        // one-time migration from v1.0.x keys (same mapping as CfgProvider);
        // kept so users jumping from old releases keep their switch states
        if (!p.contains(Cfg.K_GLASS) && p.contains("master")) {
            ed.putBoolean(Cfg.K_GLASS, p.getBoolean("master", true));
            ed.putBoolean(Cfg.K_SYSTEM, true);
            ed.putBoolean(Cfg.K_KEYGUARD,
                    p.getBoolean("keyguard", true) || p.getBoolean("kgcard", true));
            ed.putBoolean(Cfg.K_GALLERY, p.getBoolean("gallery", true));
            ed.putBoolean(Cfg.K_PURIFY, true);
            ed.putBoolean(Cfg.K_INFOCARD, p.getBoolean("infocard", true));
            ed.putBoolean(Cfg.K_DOWNGRADE, p.getBoolean("downgrade", true));
            for (String old : new String[]{"master", "keyguard", "kgcard", "gallery",
                    "skipcheck", "nonet", "infocard", "downgrade", "hideextras"}) {
                ed.remove(old);
            }
            dirty = true;
        }
        for (String k : Cfg.KEYS) {
            if (!p.contains(k)) {
                // everything defaults ON except compat — auto-true here would
                // silently enable compat on old-installer devices and gate the
                // legacy set off (purify line dead). Compat must stay opt-in.
                ed.putBoolean(k, !Cfg.K_COMPAT.equals(k));
                dirty = true;
            }
        }
        if (dirty) {
            ed.commit();
            notifyCfgChanged();
        }
        relaxPrefsPerms();

        // on Android 15+ (targetSdk 35) the system-ui visibility flags below
        // are ignored and edge-to-edge is enforced; the insets controller is
        // the only way to keep dark status/nav icons in day theme there.
        // API < 30 throws and falls back to the legacy flags.
        try {
            android.view.WindowInsetsController ic = getWindow().getInsetsController();
            if (ic != null) {
                int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                ic.setSystemBarsAppearance(dark ? 0 : light, light);
            }
        } catch (Throwable ignored) {
        }
        try {
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
            getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        } catch (Throwable ignored) {
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | (dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(dark ? 0xFF000000 : 0xFFF2F4F9);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                content.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                        insets.getSystemWindowInsetBottom());
                if (navBar != null) {
                    FrameLayout.LayoutParams nlp =
                            (FrameLayout.LayoutParams) navBar.getLayoutParams();
                    nlp.bottomMargin = insets.getSystemWindowInsetBottom() + 14 * pad;
                    navBar.setLayoutParams(nlp);
                }
                return insets.consumeSystemWindowInsets();
            }
        });

        setContentView(root);
        buildPages();
    }

    // ================= plain card theme (v1.2.7 look) =================

    /** solid rounded card: white by day, near-black by night */
    private Drawable cardBg() {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(16 * pad);
        d.setColor(dark ? 0xFF12141A : 0xFFFFFFFF);
        return d;
    }

    /** small rounded square with a bold white glyph — stands in for the
     *  shield logo */
    private Bitmap brandIcon(int bgColor, String ch) {
        int s = 22 * pad;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(bgColor);
        c.drawRoundRect(0, 0, s, s, 5 * pad, 5 * pad, p);
        p.setColor(0xFFFFFFFF);
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        p.setTextSize(ch.length() > 1 ? 10 * pad : 13 * pad);
        p.setFakeBoldText(true);
        android.graphics.Paint.FontMetrics fm = p.getFontMetrics();
        float y = s / 2f - (fm.ascent + fm.descent) / 2f;
        c.drawText(ch, s / 2f, y, p);
        return b;
    }

    /** vector drawable from res/, tinted with the brand colour */
    private Bitmap vectorIcon(int resId, int tint) {
        int s = 22 * pad;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Drawable d = getResources().getDrawable(resId, getTheme());
        d.setTint(tint);
        d.setBounds(0, 0, s, s);
        d.draw(c);
        return b;
    }

    /** monochrome glyph from assets, re-tinted with the brand colour */
    private Bitmap assetIcon(String file, int tint) {
        try {
            java.io.InputStream is = getAssets().open(file);
            Bitmap src = android.graphics.BitmapFactory.decodeStream(is);
            is.close();
            if (src == null) return brandIcon(tint, "?");
            Bitmap out = Bitmap.createBitmap(
                    src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            android.graphics.Paint p = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setColorFilter(new android.graphics.PorterDuffColorFilter(
                    tint, android.graphics.PorterDuff.Mode.SRC_IN));
            c.drawBitmap(src, 0, 0, p);
            return out;
        } catch (Throwable ignored) {
            return brandIcon(tint, "?");
        }
    }

    // ================= pages =================

    private void buildPages() {
        content.removeAllViews();

        TextView title = new TextView(this);
        title.setText("荣耀高级设置");
        title.setTextSize(30);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(dark ? 0xFFF2F6FC : 0xFF0F172A);
        title.setPadding(24 * pad, 20 * pad, 24 * pad, 2 * pad);
        content.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("基于MagicOS11开发");
        subtitle.setTextSize(14);
        subtitle.setTextColor(dark ? 0x99E2E8F0 : 0x99334155);
        subtitle.setPadding(24 * pad, 0, 24 * pad, 10 * pad);
        content.addView(subtitle);

        FrameLayout pages = new FrameLayout(this);
        pageMine = buildMinePage();
        pageGlass = buildGlassPage();
        pageInstaller = buildInstallerPage();
        pageAbout = buildAboutPage();
        for (ScrollView p : new ScrollView[]{pageAbout, pageInstaller, pageGlass, pageMine}) {
            pages.addView(p, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        pageGlass.setVisibility(View.GONE);
        pageInstaller.setVisibility(View.GONE);
        pageAbout.setVisibility(View.GONE);
        content.addView(pages, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // two floating capsule switch buttons overlaying the page — the area
        // around them stays transparent; content scrolls full-screen beneath
        // (extra bottom padding keeps the last rows clear)
        FrameLayout rootLayer = (FrameLayout) content.getParent();
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        navLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        navLp.bottomMargin = 14 * pad;
        rootLayer.addView(buildNavBar(), navLp);
    }

    /** single source of truth for which page is visible */
    private void showPage(ScrollView target) {
        pageMine.setVisibility(target == pageMine ? View.VISIBLE : View.GONE);
        pageGlass.setVisibility(target == pageGlass ? View.VISIBLE : View.GONE);
        pageInstaller.setVisibility(target == pageInstaller ? View.VISIBLE : View.GONE);
        pageAbout.setVisibility(target == pageAbout ? View.VISIBLE : View.GONE);
    }

    private ScrollView buildMinePage() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(16 * pad, 10 * pad, 16 * pad, 84 * pad);
        sv.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(actionCard("液态玻璃",
                "系统 / 锁屏 / 相册三线玻璃效果与总开关", new Runnable() {
                    @Override
                    public void run() {
                        showPage(pageGlass);
                    }
                }));
        box.addView(footnote("开关改动后需重启系统界面（或重启手机）生效。"));

        box.addView(actionCard("安装器净化",
                "跳过安全检测、禁止联网、信息卡与降级安装", new Runnable() {
                    @Override
                    public void run() {
                        showPage(pageInstaller);
                    }
                }));
        box.addView(footnote("开关改动后重启作用域生效，降级安装需重启手机。"));

        box.addView(actionCard("重启作用域（需ROOT）",
                "点击申请ROOT授权，可重启系统界面 / 相册 / 安装器", new Runnable() {
                    @Override
                    public void run() {
                        requestRootThenPickScope();
                    }
                }));
        box.addView(footnote("重启作用域用于让开关改动立即生效。"));

        return sv;
    }

    /** "‹ 返回" + page title, tops every sub-page */
    private View backHeader(String title) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(4 * pad, 4 * pad, 4 * pad, 6 * pad);

        TextView back = new TextView(this);
        back.setText("‹  返回");
        back.setTextSize(15);
        back.setTextColor(ACCENT_DIM);
        back.setPadding(10 * pad, 6 * pad, 10 * pad, 6 * pad);
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(pageMine);
            }
        });
        l.addView(back);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(24);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(dark ? 0xFFF2F6FC : 0xFF0F172A);
        t.setPadding(10 * pad, 2 * pad, 10 * pad, 8 * pad);
        l.addView(t);
        return l;
    }

    private ScrollView buildGlassPage() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(16 * pad, 2 * pad, 16 * pad, 84 * pad);
        sv.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(backHeader("液态玻璃"));

        box.addView(sectionCard(null, new Object[][]{
                {"总开关", "液态玻璃总闸，含 HIGH 强制渲染、LIQUID 材质判定等系统级底层，关闭后全部回退原厂", B_GLASS},
                {"系统液态玻璃", "状态栏通知与控制中心的观感（模糊半径、遮罩、饱和度等参数）", B_SYSTEM},
                {"锁屏液态玻璃", "锁屏按钮与通知卡片的玻璃效果", B_KEYGUARD},
                {"相册液态玻璃", "相册照片页工具栏的玻璃效果", B_GALLERY},
        }, true));
        box.addView(footnote("开关改动后需重启系统界面（或重启手机）生效。"));

        return sv;
    }

    private ScrollView buildInstallerPage() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(16 * pad, 2 * pad, 16 * pad, 84 * pad);
        sv.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(backHeader("安装器净化"));

        box.addView(sectionCard(null, new Object[][]{
                {"总开关", "跳过安全检测与指纹验证、禁止联网、隐藏推广及多余卡片，关闭后全部回退原厂", B_PURIFY},
                {"安装包信息卡", "展示版本 / 大小 / SDK / 架构信息卡；关闭时同时隐藏安全模式提醒", B_INFOCARD},
                {"允许降级安装", "允许安装低于当前版本号的 APK", B_DOWNGRADE},
                {"兼容模式", "安装器为较旧版本时开启，切换到旧方案；其余开关同样生效", B_COMPAT},
        }, false));
        box.addView(footnote("开关后需重启安装器生效，降级开关需重启手机生效"));

        return sv;
    }

    // ================= about page =================

    private ScrollView buildAboutPage() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(16 * pad, 22 * pad, 16 * pad, 64 * pad);
        sv.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- card 1: donation ----
        box.addView(sectionLabel("捐赠（给AI喂点Token）"));

        LinearLayout donate = new LinearLayout(this);
        donate.setOrientation(LinearLayout.VERTICAL);
        donate.setBackground(cardBg());
        donate.setPadding(4 * pad, 4 * pad, 4 * pad, 4 * pad);
        box.addView(donate, cardLp());
        donate.addView(linkRow(assetIcon("img/icon_alipay.png", 0xFF1677FF), "支付宝", new Runnable() {
            @Override
            public void run() {
                showDonateDialog(0);
            }
        }));
        donate.addView(rowDivider());
        donate.addView(linkRow(assetIcon("img/icon_wechat.png", 0xFF07C160), "微信", new Runnable() {
            @Override
            public void run() {
                showDonateDialog(1);
            }
        }));

        // ---- card 2: links ----
        box.addView(sectionLabel("链接"));

        LinearLayout links = new LinearLayout(this);
        links.setOrientation(LinearLayout.VERTICAL);
        links.setBackground(cardBg());
        links.setPadding(4 * pad, 4 * pad, 4 * pad, 4 * pad);
        box.addView(links, cardLp());
        links.addView(linkRow(assetIcon("img/icon_github.png",
                dark ? 0xFFF2F6FC : 0xFF24292E), "GitHub 仓库", new Runnable() {
            @Override
            public void run() {
                try {
                    startActivity(new android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(
                                    "https://github.com/VoreulCH/HonorAdvancedSettings")));
                } catch (Throwable ignored) {
                }
            }
        }));
        links.addView(rowDivider());
        links.addView(linkRow(vectorIcon(R.drawable.icon_qq, 0xFF12B7F5), "联系我们", new Runnable() {
            @Override
            public void run() {
                showQqDialog();
            }
        }));
        links.addView(rowDivider());
        links.addView(linkRow(brandIcon(ACCENT, "盾"), "隐私政策", new Runnable() {
            @Override
            public void run() {
                showPrivacyDialog();
            }
        }));

        // ---- card 3: disclaimer ----
        box.addView(sectionLabel("免责声明"));

        LinearLayout disc = new LinearLayout(this);
        disc.setOrientation(LinearLayout.VERTICAL);
        disc.setBackground(cardBg());
        disc.setPadding(18 * pad, 14 * pad, 18 * pad, 14 * pad);
        box.addView(disc, cardLp());

        TextView discText = new TextView(this);
        discText.setText(
                "这是一个 Xposed 模块，仅供个人学习、研究、交流使用。\n\n"
                        + "　　使用本模块需要 LSPosed 或兼容管理器；模块通过修改系统应用的行为实现功能，"
                        + "可能存在不可预见的风险。\n\n"
                        + "　　因使用本模块造成的系统不稳定、数据丢失或其他任何损失，作者不承担任何责任。\n\n"
                        + "　　请遵守当地法律法规，禁止将本模块用于任何违法用途。");
        discText.setTextSize(13.5f);
        discText.setTextColor(dark ? 0xFFC7D2E0 : 0xFF334155);
        discText.setLineSpacing(3 * pad, 1f);
        disc.addView(discText);

        // ---- footer: version + author line only ----
        TextView version = new TextView(this);
        version.setText("v1.4.19 (42) · by VoreulCH@GitHub");
        version.setTextSize(12);
        version.setTextColor(dark ? 0x80C7D2E0 : 0x80334155);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, 14 * pad, 0, 2 * pad);
        box.addView(version);

        TextView credit = new TextView(this);
        credit.setText("AI-assisted · MIT");
        credit.setTextSize(12);
        credit.setTextColor(dark ? 0x59C7D2E0 : 0x59334155);
        credit.setGravity(Gravity.CENTER);
        credit.setPadding(0, 0, 0, 6 * pad);
        box.addView(credit);

        return sv;
    }

    // ================= glass card dialogs =================

    /** shared shell for every glass dialog: no title, transparent window,
     *  card laid out to screen width minus 72dp */
    private void showDialog(android.app.Dialog dlg, LinearLayout card) {
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        try {
            dlg.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(0x00000000));
        } catch (Throwable ignored) {
        }
        dlg.setContentView(card, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dlg.show();
        try {
            dlg.getWindow().setLayout(
                    getResources().getDisplayMetrics().widthPixels - 72 * pad,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        } catch (Throwable ignored) {
        }
    }

    private TextView dialogTitle(String text) {
        TextView head = new TextView(this);
        head.setText(text);
        head.setTextSize(18);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.setTextColor(dark ? 0xFFF2F6FC : 0xFF0F172A);
        head.setGravity(Gravity.CENTER);
        return head;
    }

    private TextView closeRow(final android.app.Dialog dlg) {
        TextView close = new TextView(this);
        close.setText("关闭");
        close.setTextSize(14);
        close.setTextColor(dark ? 0x66C7D2E0 : 0x66334155);
        close.setGravity(Gravity.CENTER);
        close.setPadding(14 * pad, 8 * pad, 14 * pad, 6 * pad);
        close.setClickable(true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
            }
        });
        return close;
    }

    /** centered muted hint used under the QR images */
    private TextView hintText(String text) {
        TextView tip = new TextView(this);
        tip.setText(text);
        tip.setTextSize(12.5f);
        tip.setTextColor(dark ? 0x99C7D2E0 : 0x99334155);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 10 * pad, 0, 4 * pad);
        return tip;
    }

    /** accent link row inside a QR dialog (scan / join) */
    private TextView jumpRow(String label, final Runnable action) {
        TextView jump = new TextView(this);
        jump.setText(label);
        jump.setTextSize(15);
        jump.setTextColor(ACCENT_DIM);
        jump.setGravity(Gravity.CENTER);
        jump.setPadding(14 * pad, 10 * pad, 14 * pad, 4 * pad);
        jump.setClickable(true);
        jump.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        return jump;
    }

    /** the one QR dialog: donate (square codes) and QQ (tall share card) all
     *  land here — title, image on a white matte, tap-to-save, jump, close */
    private void showQrDialog(String title, String file, boolean square, String saveName,
                              String jumpLabel, final Runnable onJump) {
        Bitmap bmp = null;
        try {
            java.io.InputStream is = getAssets().open(file);
            bmp = android.graphics.BitmapFactory.decodeStream(is);
            is.close();
        } catch (Throwable ignored) {
        }
        if (bmp == null) {
            Toast.makeText(this, "二维码图片缺失", Toast.LENGTH_SHORT).show();
            return;
        }

        final android.app.Dialog dlg = new android.app.Dialog(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(glassCardBig());
        card.setPadding(18 * pad, 18 * pad, 18 * pad, 12 * pad);

        card.addView(dialogTitle(title));

        // white matte so the QR reads on both themes
        GradientDrawable matte = new GradientDrawable();
        matte.setShape(GradientDrawable.RECTANGLE);
        matte.setCornerRadius(16 * pad);
        matte.setColor(0xFFFFFFFF);
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setBackground(matte);
        iv.setPadding(8 * pad, 8 * pad, 8 * pad, 8 * pad);
        // square codes take the fixed square; the tall QQ card is sized by
        // width, clamped to the card width and half the screen height
        int avail = getResources().getDisplayMetrics().widthPixels - 124 * pad;
        int w = Math.min(square ? 260 * pad : 230 * pad, avail);
        int h = square ? w : Math.round(w * bmp.getHeight() / (float) bmp.getWidth());
        if (!square) {
            int maxH = getResources().getDisplayMetrics().heightPixels / 2;
            if (h > maxH) {
                w = Math.round(w * maxH / (float) h);
                h = maxH;
            }
        }
        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(w, h);
        ivLp.gravity = Gravity.CENTER_HORIZONTAL;
        ivLp.topMargin = 12 * pad;
        card.addView(iv, ivLp);
        iv.setClickable(true);
        iv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveQrToDownload(file, saveName);
            }
        });

        card.addView(hintText("截图或点击二维码保存"));
        card.addView(jumpRow(jumpLabel, onJump));
        card.addView(closeRow(dlg));
        showDialog(dlg, card);
    }

    private void showDonateDialog(final int which) {
        final boolean alipay = which == 0;
        showQrDialog(alipay ? "支付宝收款" : "微信赞赏",
                alipay ? "img/donate_alipay.jpg" : "img/donate_wechat.png",
                true,
                alipay ? "支付宝收款" : "微信赞赏",
                alipay ? "打开支付宝扫一扫" : "打开微信扫一扫",
                new Runnable() {
                    @Override
                    public void run() {
                        if (alipay) openAlipayScan();
                        else openWechatScan();
                    }
                });
    }

    private void showQqDialog() {
        showQrDialog("联系我们", "img/qq_group.jpg", false, "QQ群二维码",
                "打开加群链接（跳转浏览器）", new Runnable() {
                    @Override
                    public void run() {
                        openQqJoinLink();
                    }
                });
    }

    private void showPrivacyDialog() {
        final android.app.Dialog dlg = new android.app.Dialog(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(glassCardBig());
        card.setPadding(18 * pad, 18 * pad, 18 * pad, 12 * pad);

        card.addView(dialogTitle("隐私政策"));

        TextView msg = new TextView(this);
        msg.setText("本模块完全运行于本机，不申请网络权限：\n\n"
                + "· 不联网、不上传、不收集任何数据\n"
                + "· 开关状态仅保存在本机应用私有目录\n"
                + "· 仅在所声明的作用域进程内运行钩子逻辑");
        msg.setTextSize(13.5f);
        msg.setTextColor(dark ? 0xFFC7D2E0 : 0xFF334155);
        msg.setLineSpacing(3 * pad, 1f);
        msg.setPadding(4 * pad, 10 * pad, 4 * pad, 6 * pad);
        card.addView(msg);

        card.addView(closeRow(dlg));
        showDialog(dlg, card);
    }

    // ---- QR dialog jump targets ----

    private void openAlipayScan() {
        try {
            startActivity(new android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(
                            "alipays://platformapi/startapp?saId=10000007")));
        } catch (Throwable ignored) {
            Toast.makeText(this, "未检测到支付宝，请手动打开扫一扫",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void openWechatScan() {
        boolean ok = false;
        try {
            android.content.Intent launch = getPackageManager()
                    .getLaunchIntentForPackage("com.tencent.mm");
            if (launch != null) {
                startActivity(launch);
                ok = true;
            }
        } catch (Throwable ignored) {
        }
        Toast.makeText(this,
                ok ? "微信已打开，请点右上角 + → 扫一扫"
                   : "未检测到微信，请手动打开扫一扫",
                Toast.LENGTH_LONG).show();
    }

    private void openQqJoinLink() {
        try {
            startActivity(new android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(QQ_JOIN_URL)));
        } catch (Throwable ignored) {
            Toast.makeText(this, "未检测到浏览器，请手动搜索群 297649230",
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ================= QR image → Download =================

    private String pendingQrFile;
    private String pendingQrName;

    /** saves a QR image from assets into the public Download directory
     *  (MediaStore.Downloads on API 29+, plain file write below) */
    private void saveQrToDownload(final String file, final String name) {
        if (android.os.Build.VERSION.SDK_INT < 29
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingQrFile = file;
            pendingQrName = name;
            requestPermissions(new String[]{
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 71);
            return;
        }
        final android.content.ContentResolver cr = getContentResolver();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    java.io.InputStream is = getAssets().open(file);
                    Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is);
                    is.close();
                    if (bmp != null) {
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            android.content.ContentValues cv =
                                    new android.content.ContentValues();
                            cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME,
                                    name + ".png");
                            cv.put(android.provider.MediaStore.Downloads.MIME_TYPE,
                                    "image/png");
                            android.net.Uri uri = cr.insert(
                                    android.provider.MediaStore.Downloads
                                            .EXTERNAL_CONTENT_URI, cv);
                            if (uri != null) {
                                java.io.OutputStream os = cr.openOutputStream(uri);
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                                os.close();
                                ok = true;
                            }
                        } else {
                            java.io.File dir = android.os.Environment
                                    .getExternalStoragePublicDirectory(
                                            android.os.Environment.DIRECTORY_DOWNLOADS);
                            if (!dir.exists()) dir.mkdirs();
                            java.io.File out = new java.io.File(dir, name + ".png");
                            java.io.FileOutputStream fos =
                                    new java.io.FileOutputStream(out);
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                            fos.close();
                            ok = true;
                        }
                    }
                } catch (Throwable ignored) {
                }
                final boolean saved = ok;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(SettingsActivity.this,
                                saved ? "已保存到 Download 目录" : "保存失败，请重试",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode != 71) return;
        if (grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
                && pendingQrFile != null) {
            String f = pendingQrFile;
            String n = pendingQrName;
            pendingQrFile = null;
            pendingQrName = null;
            saveQrToDownload(f, n);
        } else {
            pendingQrFile = null;
            pendingQrName = null;
            Toast.makeText(this, "没有存储权限，无法保存", Toast.LENGTH_SHORT).show();
        }
    }

    // ================= row / card builders =================

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(dark ? 0x99C7D2E0 : 0x99334155);
        tv.setPadding(6 * pad, 6 * pad, 6 * pad, 6 * pad);
        return tv;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 6 * pad;
        return lp;
    }

    private View rowDivider() {
        View d = new View(this);
        d.setBackgroundColor(dark ? 0x14FFFFFF : 0x14000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = 14 * pad;
        lp.rightMargin = 10 * pad;
        d.setLayoutParams(lp);
        return d;
    }

    /** icon + label + chevron row, xradiant list style */
    private View linkRow(Bitmap icon, String label, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(12 * pad, 11 * pad, 12 * pad, 11 * pad);
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });

        ImageView iv = new ImageView(this);
        iv.setImageBitmap(icon);
        row.addView(iv, new LinearLayout.LayoutParams(22 * pad, 22 * pad));

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(15);
        t.setTextColor(dark ? 0xFFF2F6FC : 0xFF0F172A);
        t.setPadding(14 * pad, 0, 0, 0);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(dark ? 0x66C7D2E0 : 0x66334155);
        row.addView(arrow);
        return row;
    }

    private View sectionCard(String header, Object[][] items, boolean dividers) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg());
        card.setPadding(6 * pad, 10 * pad, 6 * pad, 6 * pad);

        if (header != null) {
            TextView head = new TextView(this);
            head.setText(header);
            head.setTextSize(13);
            head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            head.setTextColor(ACCENT_DIM);
            head.setPadding(18 * pad, 8 * pad, 18 * pad, 8 * pad);
            card.addView(head);
        }

        for (int i = 0; i < items.length; i++) {
            final String t = (String) items[i][0];
            final String s = (String) items[i][1];
            final Binding b = (Binding) items[i][2];
            card.addView(switchRow(t, s, b));
            if (dividers && i < items.length - 1) {
                View d = new View(this);
                d.setBackgroundColor(dark ? 0x14FFFFFF : 0x14000000);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1);
                lp.leftMargin = 18 * pad;
                lp.rightMargin = 18 * pad;
                card.addView(d, lp);
            }
        }
        return card;
    }

    private View switchRow(String title, String sub, final Binding b) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(18 * pad, 12 * pad, 14 * pad, 12 * pad);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(dark ? 0xFFF2F6FC : 0xFF0F172A);
        texts.addView(t);

        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(12.5f);
        s.setTextColor(dark ? 0x99C7D2E0 : 0x99334155);
        s.setPadding(0, 3 * pad, 20 * pad, 0);
        s.setLineSpacing(2 * pad, 1f);
        texts.addView(s);

        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch sw = new Switch(this);
        sw.setPadding(8 * pad, 0, 8 * pad, 0);
        try {
            int[][] states = {{android.R.attr.state_checked}, {}};
            sw.setTrackTintList(new android.content.res.ColorStateList(states,
                    new int[]{ACCENT, dark ? 0xFF3A4354 : 0xFFD3DCE8}));
            sw.setThumbTintList(new android.content.res.ColorStateList(states,
                    new int[]{0xFFFFFFFF, dark ? 0xFFAEB9C9 : 0xFFE8EEF6}));
        } catch (Throwable ignored) {
        }
        // set before the listener so restoring the stored state fires nothing
        sw.setChecked(prefsOn(b.key, b.def));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean isChecked) {
                applyBinding(b, isChecked);
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return row;
    }

    private View footnote(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(dark ? 0x66E2E8F0 : 0x665E6B7E);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(10 * pad, 5 * pad, 10 * pad, 4 * pad);
        tv.setLineSpacing(2 * pad, 1f);
        return tv;
    }

    /** a clickable card for actions like scope restart: title row with a
     *  trailing chevron, then the description on its own full-width line */
    private View actionCard(String title, String sub, final Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg());
        card.setPadding(18 * pad, 14 * pad, 16 * pad, 14 * pad);
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(dark ? 0xFFF2F6FC : 0xFF0F172A);
        titleRow.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(22);
        arrow.setTextColor(dark ? 0x66FFFFFF : 0x660F172A);
        arrow.setPadding(4 * pad, 0, 0, 0);
        titleRow.addView(arrow);
        card.addView(titleRow);

        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(12.5f);
        s.setTextColor(dark ? 0x99C7D2E0 : 0x99334155);
        s.setPadding(0, 4 * pad, 10 * pad, 0);
        s.setLineSpacing(3 * pad, 1f);
        card.addView(s);
        return card;
    }

    // ================= root: restart scoped processes =================

    private static final String[] SCOPE_NAMES = {"系统界面", "相册", "安装器"};
    private static final String[] SCOPE_CMDS = {
            "killall com.android.systemui",
            "am force-stop com.hihonor.photos",
            "am force-stop com.android.packageinstaller",
    };

    /**
     * Run cmd via su. The first su call raises the manager's grant dialog
     * (that is the intended "request root" step); denial or missing su
     * returns false. Output is drained so the pipe can never fill up.
     */
    private boolean suExec(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            java.io.InputStream is = p.getInputStream();
            byte[] buf = new byte[512];
            while (is.read(buf) >= 0) {
            }
            return p.waitFor() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private void requestRootThenPickScope() {
        Toast.makeText(this, "正在检查ROOT权限…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean root = suExec("id");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!root) {
                            Toast.makeText(SettingsActivity.this,
                                    "没有root权限", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        showRestartDialog();
                    }
                });
            }
        }).start();
    }

    /** glass-card replacement for the stock scope picker dialog */
    private void showRestartDialog() {
        final android.app.Dialog dlg = new android.app.Dialog(this);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(glassCardBig());
        card.setPadding(10 * pad, 16 * pad, 10 * pad, 8 * pad);

        TextView title = new TextView(this);
        title.setText("重启作用域");
        title.setTextSize(18);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(dark ? 0xFFF2F6FC : 0xFF0F172A);
        title.setPadding(14 * pad, 4 * pad, 14 * pad, 2 * pad);
        card.addView(title);

        TextView sub = new TextView(this);
        sub.setText("选择要重启的应用");
        sub.setTextSize(12);
        sub.setTextColor(dark ? 0x99C7D2E0 : 0x99334155);
        sub.setPadding(14 * pad, 0, 14 * pad, 10 * pad);
        card.addView(sub);

        for (int i = 0; i < SCOPE_NAMES.length; i++) {
            final int which = i;
            TextView item = new TextView(this);
            item.setText(SCOPE_NAMES[i]);
            item.setTextSize(16);
            item.setTextColor(dark ? 0xFFE2E8F0 : 0xFF1E293B);
            item.setPadding(14 * pad, 13 * pad, 14 * pad, 13 * pad);
            item.setClickable(true);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dlg.dismiss();
                    restartScope(which);
                }
            });
            card.addView(item);
            if (i < SCOPE_NAMES.length - 1) {
                View d = new View(this);
                d.setBackgroundColor(dark ? 0x14FFFFFF : 0x14000000);
                card.addView(d, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
        }

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(15);
        cancel.setTextColor(ACCENT_DIM);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(14 * pad, 12 * pad, 14 * pad, 6 * pad);
        cancel.setClickable(true);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
            }
        });
        card.addView(cancel);

        showDialog(dlg, card);
    }

    /** larger glass card: translucent fill + hairline rim + elevation */
    private Drawable glassCardBig() {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(24 * pad);
        d.setColor(dark ? 0xF212141A : 0xF2FFFFFF);
        d.setStroke(Math.max(1, pad), dark ? 0x59FFFFFF : 0xCCFFFFFF);
        return d;
    }

    private void restartScope(final int which) {
        final String name = SCOPE_NAMES[which];
        Toast.makeText(this, "正在重启" + name + "…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = suExec(SCOPE_CMDS[which]);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(SettingsActivity.this,
                                ok ? "已重启" + name : "重启失败：ROOT授权被拒绝",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    // ================= bottom nav (segmented capsule switch) =================

    private FrameLayout buildNavBar() {
        // ONE capsule tray holding both segments; the selected segment gets
        // a solid inner capsule. Everything around the tray stays transparent
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(4 * pad, 4 * pad, 4 * pad, 4 * pad);

        GradientDrawable tray = new GradientDrawable();
        tray.setShape(GradientDrawable.RECTANGLE);
        tray.setCornerRadius(20 * pad);
        tray.setColor(dark ? 0xFF12141A : 0xFFFFFFFF);
        tray.setStroke(Math.max(1, pad), dark ? 0x33FFFFFF : 0x2E0F172A);
        pill.setBackground(tray);

        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 32 * pad);
        lp1.rightMargin = 4 * pad;
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 32 * pad);
        lp2.leftMargin = 4 * pad;
        pill.addView(navTab("我的", true), lp1);
        pill.addView(navTab("关于", false), lp2);
        restyleTabs(true);

        FrameLayout host = new FrameLayout(this);
        host.addView(pill, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        navBar = host;
        return host;
    }

    private View navTab(String label, final boolean isMine) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(14);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(22 * pad, 0, 22 * pad, 0);
        tv.setClickable(true);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(isMine ? pageMine : pageAbout);
                restyleTabs(isMine);
            }
        });
        if (isMine) tabMineText = tv; else tabAboutText = tv;
        return tv;
    }

    /** segmented look: selected = solid capsule chip, idle = plain text */
    private void restyleTabs(boolean mineSelected) {
        styleTab(tabMineText, mineSelected);
        styleTab(tabAboutText, !mineSelected);
    }

    private void styleTab(TextView tv, boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(16 * pad);
        if (selected) {
            // same light blue as the switches on the subpages (#6492EF,
            // sampled live) so the whole app speaks one accent colour
            d.setColor(0xFF6492EF);
            tv.setTextColor(0xFFFFFFFF);
        } else {
            d.setColor(0x00000000);
            tv.setTextColor(dark ? 0x99C7D2E0 : 0x66334155);
        }
        tv.setBackground(d);
    }

    // ================= switch write =================

    private void applyBinding(final Binding b, final boolean on) {
        getSharedPreferences(Cfg.PREFS_FILE, MODE_PRIVATE)
                .edit().putBoolean(b.key, on).commit();
        relaxPrefsPerms();
        notifyCfgChanged();
        String tail = b.restart
                ? "，重启系统界面（或手机）后生效"
                : "，下次安装即生效";
        Toast.makeText(this, (on ? "已开启" : "已关闭") + tail, Toast.LENGTH_SHORT).show();
    }

    /** wake installer-scope observers (system_server / packageinstaller) so
     *  the new value applies to the next install without a restart; glass
     *  scopes pick it up on their next process start by design. */
    private void notifyCfgChanged() {
        try {
            getContentResolver().notifyChange(CfgProvider.URI, null);
        } catch (Throwable ignored) {
        }
    }

    /** best-effort: let managers without prefs serving read the file directly. */
    private void relaxPrefsPerms() {
        try {
            java.io.File dataDir = new java.io.File(getApplicationInfo().dataDir);
            dataDir.setExecutable(true, false);
            java.io.File prefsDir = new java.io.File(dataDir, "shared_prefs");
            prefsDir.setExecutable(true, false);
            new java.io.File(prefsDir, Cfg.PREFS_FILE + ".xml").setReadable(true, false);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onBackPressed() {
        if (pageGlass.getVisibility() == View.VISIBLE
                || pageInstaller.getVisibility() == View.VISIBLE) {
            showPage(pageMine);
            return;
        }
        super.onBackPressed();
    }
}
