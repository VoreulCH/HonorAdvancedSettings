package io.github.voreulch.honoradvanced;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * Serves switch values to hooked processes. On this device SELinux MCS
 * blocks direct reads of the module's config.xml and Vector lacks
 * LSPosed's prefs serving, so hooks fetch the values over the standard
 * binder channel instead. Read-only, no permission required.
 */
public final class CfgProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.voreulch.honoradvanced.cfg";
    public static final Uri URI =
            Uri.parse("content://" + AUTHORITY + "/switches");

    /**
     * 作用域自证通道（v2.3.0）。
     *
     * <p>每个被 hook 的进程启动时 insert 一行 "{pkg, ver}"，写入发生在**模块 app
     * 进程**（provider 天然跑在自己的进程里），hook 侧只出一次 binder 调用。
     * 于是模块界面不用 root、不用读 LSPosed 数据库，就能知道"这个作用域是不是
     * 真的跑起来了"——而且它验证的是**实际生效**，不是"勾选过"：
     * 勾了但进程没重启时，这里会诚实地显示未生效。
     */
    public static final Uri URI_PROBE =
            Uri.parse("content://" + AUTHORITY + "/probe");

    /** 上报存这里（与开关分开，避免污染开关快照）。 */
    public static final String PREFS_PROBE = "probe";

    public static final String COL_PKG = "pkg";
    public static final String COL_VER = "ver";
    // 探针记录从 ver|wallclock 升级为
    // ver|pid|boot_id|进程启动时刻|心跳时间，判活靠"本次开机 + 最近心跳"。
    public static final String COL_PID = "pid";
    public static final String COL_BOOT = "boot";
    public static final String COL_START = "start";
    public static final String COL_BEAT = "beat";
    /**
     * 框架代次（216 起）。system_server 每次加载模块写一个新的
     * {@code persist.sys.has.framework_epoch}（pid:startAt:time），宿主上报时带上。
     * 软重启不改变 boot_id，只有 epoch 能证明"框架确实换代了"。
     */
    public static final String COL_EPOCH = "epoch";

    @Override
    public boolean onCreate() {
        // v3.0.5：高光语义反转的老数据迁移必须在这里做 —— 这是模块 app 进程
        // 最早能拿到 prefs 的地方，而且不依赖用户打开界面。hook 侧读 provider
        // 时也会间接把它触发起来。
        try {
            android.content.Context c = getContext();
            if (c != null) {
                android.content.SharedPreferences sp =
                        c.getSharedPreferences(Cfg.PREFS_FILE, android.content.Context.MODE_PRIVATE);
                // ⚠ 顺序：**先**做高光语义迁移，**再**落烧录配方。
                //
                // 为什么要这个顺序：migrateHlSemantics 的 else 分支会无条件写
                // fn_hl_on = 1，如果放在后面，就会把刚写好的配方 1 当成"旧语义的
                // 关闭"再翻成 0（新装用户四个高光开关全灭）。
                //
                // 那为什么不怕它干扰"老用户"判定？因为 migrateBakedParams 判断时
                // 已经**排除高光四键**（那四个是迁移自己写的，不能算用户调过）。
                Cfg.migrateHlSemantics(sp);
                Cfg.migrateBakedParams(sp);
                // 锁屏按钮历史烧录值 -> 原厂等价（只重置恰好等于历史值的项）。
                Cfg.migrateKgBtnDefaults(sp);
                // epoch 的唯一来源是系统属性；prefs 里若残留 glass_epoch 就一次性清掉，
                // 避免出现第二份状态。清不掉也不影响运行（代码不读它）。
                if (sp.contains("glass_epoch")) {
                    sp.edit().remove("glass_epoch").apply();
                }
                // Subject 链路不存在；prefs 里若残留 noawareness_subject 就一次性清掉，
                // 避免留下死键。
                if (sp.contains("noawareness_subject")) {
                    sp.edit().remove("noawareness_subject").apply();
                }
                // 降级安装默认允许。system_server 读不到 provider，
                // 只能靠属性；属性必须在模块进程被拉起时就对齐，不能等用户
                // 打开设置页（pm clear 后重启、不打开设置也必须生效）。
                // 必须异步：su 首次授权会卡住 provider.onCreate，宿主查询超时。
                AppLog.INSTANCE.init(c);
                syncDowngradePropAsync(c);
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /**
     * 把 {@link Cfg#PROP_DOWNGRADE} 对齐到 prefs。
     *
     * <p>缺省值统一为 true（新装 / pm clear 后没有任何键）；老用户 prefs 里
     * 显式写了 false 的保持 false，不覆盖。
     *
     * <p>这个线程只跑一次（provider.onCreate 每次进程启动只执行一次），
     * 写失败只记日志、不抛异常 —— 下次打开设置页或宿主查询时还会重试。
     */
    private static void syncDowngradePropAsync(final Context ctx) {
        try {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        SharedPreferences sp = ctx.getSharedPreferences(
                                Cfg.PREFS_FILE, Context.MODE_PRIVATE);
                        String want = sp.getBoolean(Cfg.K_DOWNGRADE, true) ? "1" : "0";
                        String before = getProp(Cfg.PROP_DOWNGRADE);
                        if (want.equals(before)) {
                            note("provider 属性已对齐=" + want + "，跳过");
                            return;
                        }
                        Process p = new ProcessBuilder("su", "-c",
                                "setprop " + Cfg.PROP_DOWNGRADE + " " + want)
                                .redirectErrorStream(true).start();
                        boolean exited = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
                        int code = exited ? p.exitValue() : -1;
                        if (!exited) {
                            try { p.destroy(); } catch (Throwable ignored) {
                            }
                        }
                        String now = getProp(Cfg.PROP_DOWNGRADE);
                        boolean ok = exited && code == 0 && want.equals(now);
                        note("provider 同步 目标=" + want + " 原=" + before
                                + " 回读=" + now + " -> " + (ok ? "成功" : "失败"));
                    } catch (Throwable t) {
                        note("provider 同步异常: " + t);
                    }
                }
            }, "has-downgrade-provider").start();
        } catch (Throwable t) {
            note("provider 同步启动失败: " + t);
        }
    }

    private static String getProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class)
                    .invoke(null, key, "");
            return v == null ? "" : String.valueOf(v).trim();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void note(String msg) {
        try {
            AppLog.INSTANCE.d("downgrade", msg);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (URI.equals(uri) || URI_PROBE.equals(uri)) {
            note("query " + (URI_PROBE.equals(uri) ? "probe" : "switches")
                    + " callerUid=" + android.os.Binder.getCallingUid());
        }
        if (URI_PROBE.equals(uri)) {
            // 探针表只给模块自身 / root / shell 读。
            if (!probeCallerAllowed()) {
                return new MatrixCursor(new String[]{
                        COL_PKG, COL_VER, COL_PID, COL_BOOT, COL_START, COL_BEAT, COL_EPOCH}, 0);
            }
            return probeCursor();
        }
        if (URI.equals(uri) && !switchCallerAllowed()) {
            // 开关表只给模块自身、root/shell、system_server 与实际作用域包读；
            // 第三方应用拿到的永远是空表，读不到模块配置。
            note("switches denied uid=" + android.os.Binder.getCallingUid());
            return new MatrixCursor(new String[]{"key", "val"}, 0);
        }
        MatrixCursor cursor = new MatrixCursor(
                new String[]{"key", "val"},
                Cfg.KEYS.length + Cfg.TUNE_KEYS.length + Cfg.FN_KEYS.length);
        Context context = getContext();
        if (context != null) {
            SharedPreferences prefs = context.getSharedPreferences(
                    Cfg.PREFS_FILE, Context.MODE_PRIVATE);
            for (String key : Cfg.KEYS) {
                cursor.addRow(new Object[]{key, migrated(prefs, key) ? 1 : 0});
            }
            for (String key : Cfg.TUNE_KEYS) {
                cursor.addRow(new Object[]{key, prefs.getInt(key, Cfg.tuneDefPct(key))});
            }
            for (String key : Cfg.FN_KEYS) {
                cursor.addRow(new Object[]{key, prefs.getInt(key, Cfg.fnDefPct(key))});
            }
        }
        return cursor;
    }

    /** 已上报的作用域（pkg / 模块版本 / 上报时间）。 */
    private Cursor probeCursor() {
        MatrixCursor c = new MatrixCursor(new String[]{
                COL_PKG, COL_VER, COL_PID, COL_BOOT, COL_START, COL_BEAT, COL_EPOCH},
                Cfg.SCOPES.length);
        Context ctx = getContext();
        if (ctx == null) {
            return c;
        }
        SharedPreferences p = ctx.getSharedPreferences(PREFS_PROBE, Context.MODE_PRIVATE);
        for (String pkg : Cfg.SCOPES) {
            String raw = p.getString(pkg, null);
            if (raw == null) {
                continue;
            }
            int ver = 0;
            int pid = 0;
            String boot = "";
            long start = 0L;
            long beat = 0L;
            String epoch = "";
            String[] parts = raw.split("\\|", -1);
            if (parts.length >= 6) {
                try { ver = Integer.parseInt(parts[0]); } catch (Throwable ignored) {}
                try { pid = Integer.parseInt(parts[1]); } catch (Throwable ignored) {}
                boot = parts[2] == null ? "" : parts[2];
                try { start = Long.parseLong(parts[3]); } catch (Throwable ignored) {}
                try { beat = Long.parseLong(parts[4]); } catch (Throwable ignored) {}
                epoch = parts[5] == null ? "" : parts[5].trim();
            } else if (parts.length == 5) {
                // 216 之前的记录没有 epoch：照常解析，但判新鲜会因 epoch 为空而失效。
                try { ver = Integer.parseInt(parts[0]); } catch (Throwable ignored) {}
                try { pid = Integer.parseInt(parts[1]); } catch (Throwable ignored) {}
                boot = parts[2] == null ? "" : parts[2];
                try { start = Long.parseLong(parts[3]); } catch (Throwable ignored) {}
                try { beat = Long.parseLong(parts[4]); } catch (Throwable ignored) {}
            } else if (parts.length == 2) {
                // 旧格式 ver|wallclock：boot 留空，UI 会判成"本次开机未上报"
                try { ver = Integer.parseInt(parts[0]); } catch (Throwable ignored) {}
                try { beat = Long.parseLong(parts[1]); } catch (Throwable ignored) {}
            }
            c.addRow(new Object[]{pkg, ver, pid, boot, start, beat, epoch});
        }
        return c;
    }

    /** /probe 只给模块自身 / root / shell 读，第三方 app 看不到宿主指纹。 */
    private static boolean probeCallerAllowed() {
        int uid = android.os.Binder.getCallingUid();
        return uid == android.os.Process.myUid() || uid == 0 || uid == 2000;
    }

    /**
     * /switches 的读权限：模块自身 / root / shell / system_server，
     * 以及 Cfg.SCOPES 里声明过的实际作用域包。别的第三方 app 一律空表。
     */
    private boolean switchCallerAllowed() {
        int uid = android.os.Binder.getCallingUid();
        if (uid == android.os.Process.myUid() || uid == 0 || uid == 2000
                || uid == 1000) {
            return true;
        }
        Context ctx = getContext();
        if (ctx == null) return false;
        try {
            String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
            if (pkgs == null) return false;
            for (String p : pkgs) {
                if (p == null) continue;
                if (ctx.getPackageName().equals(p)
                        || isKnownScope(p)
                        || "android".equals(p)
                        || "com.android.packageinstaller".equals(p)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 只有声明过的宿主包名能上报，别的 app 塞不进来。 */
    private static boolean isKnownScope(String pkg) {
        for (String s : Cfg.SCOPES) {
            if (s.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * v1.0.x prefs carried different keys (master/keyguard/kgcard/skipcheck/
     * nonet/hideextras). Serve the migrated view without writing: new key
     * wins if present, else derive from the legacy key, else default ON.
     */
    private static boolean migrated(SharedPreferences p, String key) {
        if (p.contains(key)) {
            return p.getBoolean(key, true);
        }
        if (Cfg.K_GLASS.equals(key)) {
            // 区分「老用户升级」与「全新安装」。
            // 有旧 master 键 -> 尊重旧值；没有 master 但有其它 v1 旧键 -> 老用户，默认开；
            // 什么旧键都没有 -> 全新安装，按 opt-in 默认关。
            if (p.contains("master")) {
                return p.getBoolean("master", false);
            }
            boolean legacy = p.contains("keyguard") || p.contains("kgcard")
                    || p.contains("gallery") || p.contains("infocard")
                    || p.contains("downgrade") || p.contains("skipcheck")
                    || p.contains("nonet") || p.contains("hideextras");
            return legacy;
        }
        if (Cfg.K_KEYGUARD.equals(key)) {
            return p.getBoolean("keyguard", true) || p.getBoolean("kgcard", true);
        }
        if (Cfg.K_GALLERY.equals(key)) {
            return p.getBoolean("gallery", true);
        }
        if (Cfg.K_INFOCARD.equals(key)) {
            return p.getBoolean("infocard", true);
        }
        if (Cfg.K_DOWNGRADE.equals(key)) {
            // 默认允许降级。没有键 = 新装 / pm clear -> true；
            // 老用户明确写过 false 的仍返回 false（不覆盖人家的选择）。
            return p.getBoolean("downgrade", true);
        }
        // 4 个 opt-in 开关（DIY 总闸 / 不压低音量 / 高温旁路 / 诊断日志）统一由
        // Cfg.isOptIn 判定，别再在这里各写一个 if —— 那是三份重复逻辑的来源。
        if (Cfg.isOptIn(key)) {
            return false;
        }
        return true; // K_SYSTEM / K_PURIFY had no legacy counterpart
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.voreulch.has.switches";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (!URI_PROBE.equals(uri) || values == null) {
            return null;
        }
        String pkg = values.getAsString(COL_PKG);
        if (pkg == null || !isKnownScope(pkg)) {
            return null;
        }
        Integer ver = values.getAsInteger(COL_VER);
        // 拒绝未来版本 / 非法版本，防止伪造"高版本已注入"。
        if (ver == null || ver <= 0 || ver > BuildConfig.VERSION_CODE) {
            return null;
        }
        Context ctx = getContext();
        if (ctx == null) {
            return null;
        }
        // 校验调用方身份。目标包名必须属于 callingUid 的包集合；
        // 模块自身 / root / shell 例外（root/shell 仅用于自检脚本）。
        int callingUid = android.os.Binder.getCallingUid();
        if (callingUid != android.os.Process.myUid()
                && callingUid != 0 && callingUid != 2000) {
            try {
                String[] owned = ctx.getPackageManager().getPackagesForUid(callingUid);
                boolean match = false;
                if (owned != null) {
                    for (String o : owned) {
                        if (pkg.equals(o)) {
                            match = true;
                            break;
                        }
                    }
                }
                if (!match) {
                    return null;
                }
            } catch (Throwable t) {
                return null;
            }
        }
        Integer pidV = values.getAsInteger(COL_PID);
        int pid = pidV == null ? 0 : pidV;
        String boot = values.getAsString(COL_BOOT);
        if (boot == null || boot.length() > 64) {
            boot = "";
        }
        Long startV = values.getAsLong(COL_START);
        long start = startV == null ? 0L : startV;
        String epoch = values.getAsString(COL_EPOCH);
        if (epoch == null || epoch.length() > 64) {
            epoch = "";
        }
        // epoch 只允许 pid:startAt:time 这类字符，别把任意文本写进 prefs。
        epoch = epoch.replaceAll("[^0-9A-Za-z:_-]", "");
        // 心跳时间用服务端时间，不信任客户端传来的 beat。
        long now = System.currentTimeMillis();
        String rec = ver + "|" + pid + "|" + boot + "|" + start + "|" + now + "|" + epoch;
        boolean ok = ctx.getSharedPreferences(PREFS_PROBE, Context.MODE_PRIVATE)
                .edit()
                .putString(pkg, rec)
                .commit();
        return ok ? uri : null;
    }

    /**
     * 主动清掉探针记录（模块 App 自己调用，不需要 root）。
     *
     * <p>重启作用域 / 软重启前必须调用：旧记录留在 5 分钟窗口里会冒充"新鲜"，
     * 首页就会假绿或永远卡在"心跳暂停"。传空集合 = 清全部。
     */
    public static int clearProbe(Context ctx, String... pkgs) {
        if (ctx == null) {
            return 0;
        }
        SharedPreferences p = ctx.getSharedPreferences(PREFS_PROBE, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();
        if (pkgs == null || pkgs.length == 0) {
            e.clear();
        } else {
            for (String pkg : pkgs) {
                if (pkg != null && isKnownScope(pkg)) {
                    e.remove(pkg);
                }
            }
        }
        return e.commit() ? 1 : 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}
