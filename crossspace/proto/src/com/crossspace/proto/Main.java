package com.crossspace.proto;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Build;
import android.os.UserHandle;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 跨空间调用选择器 —— 原型。
 *
 * 规则（不按支付场景判断）：
 *  1) 任何外部应用发起的、解析结果落在「微信 / 支付宝」的 intent，只要该包在多个空间装有，
 *     就把其它空间的同类 Activity 追加进候选，并强制走系统选择器；
 *  2) 显式带 component 但不带 user 的调用同样处理（微信 SDK 就是显式 component）；
 *  3) 排除：系统自身、Launcher、选择器自己、以及我们自己已经强制过一次的 intent（防死循环）。
 *
 * 注入点：system_server 的 PackageManagerService$ComputerEngine#queryIntentActivitiesInternal
 * 强制弹框：com.android.server.am.OplusMultiAppManagerService#changeIntentIfNeed
 */
public class Main implements IXposedHookLoadPackage {

    static final String TAG = "[CrossSpaceProto] ";
    /** 用于确认设备上跑的确实是新编译的代码（改一处逻辑就换一个 rev 字符串）。 */
    static final String REV = "2026-11-10T1915-fileu0b";

    /** 目标应用：跨空间调用的对象（不是"支付白名单"，是这套规则的作用对象） */
    static final String[] WATCH_PKGS = {
            "com.tencent.mm",                 // 微信
            "com.eg.android.AlipayGphone"     // 支付宝
    };

    /** 候选空间：0 为主空间，其余为 clone profile */
    static final int[] SPACE_USERS = {
            999, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30
    };

    /** 已强制过 chooser 的标记，防止选择器内部再次触发 */
    static final String FORCE_KEY = "com.crossspace.proto.forced";

    static ClassLoader SYS_CL;

    /** 是否给注入条目改写带空间名的标签 */
    static final boolean RELABEL = true;

    static volatile boolean FIELD_LOGGED;
    static volatile boolean ADAPTER_LOGGED;
    static volatile boolean ITEM_LOGGED;
    static volatile int MR_LOGGED;

    /** 防递归：钩子里再调 PMS */
    static final ThreadLocal<Boolean> GUARD = new ThreadLocal<Boolean>();

    /** 记录我们注入过的 ResolveInfo，避免对同一结果重复追加 */
    static final Map<ResolveInfo, Integer> INJECTED =
            Collections.synchronizedMap(new IdentityHashMap<ResolveInfo, Integer>());

    /** 复用 ComputerEngine 实例做跨 user 查询 */
    static volatile Object ENGINE;
    static volatile Method ENGINE_QIA4;

    /** intent 签名 -> {时间戳, 空间数}，避免每次启动都全量扫用户 */
    static final Map<String, long[]> SPACE_CACHE = new ConcurrentHashMap<String, long[]>();
    static final long CACHE_TTL_MS = 3000L;

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        if (lp == null || lp.packageName == null) return;
        if (!"android".equals(lp.packageName) && !"com.android.intentresolver".equals(lp.packageName)) return;

        log("loaded pkg=" + lp.packageName + " proc=" + lp.processName
                + " sdk=" + Build.VERSION.SDK_INT + " release=" + Build.VERSION.RELEASE);
        logf("BOOT rev=" + REV + " pkg=" + lp.packageName);
        if ("android".equals(lp.packageName)) dumpCallerSigs(lp.classLoader);

        try {
            if ("android".equals(lp.packageName)) {
                hookSystemServer(lp.classLoader);
            } else {
                hookResolver(lp.classLoader);
            }
        } catch (Throwable t) {
            log("init failed: " + t);
        }
    }

    // ---------------------------------------------------------------- system_server

    /**
     * 用 ATMS#startActivityAsCaller 回放：caller/resultTo 等参数按 Android 14/15 的原型拼，
     * ATMS 内部会用「resultTo 这个选择器 Activity 的真正原始调用方」身份来启动与授权。
     */
    static int startAsCaller(Intent fi, String callingPkg, int user) {
        try {
            android.os.IBinder tok = null;
            try {
                Object o = fi.getExtras() == null ? null
                        : fi.getExtras().get("com.crossspace.proto.token");
                if (o instanceof android.os.IBinder) tok = (android.os.IBinder) o;
            } catch (Throwable ignored) {
            }
            if (tok == null) {
                logf("asc no token");
                return -99;
            }
            Object atm = Class.forName("android.app.ActivityTaskManager")
                    .getMethod("getService").invoke(null);
            java.lang.reflect.Method m = null;
            for (java.lang.reflect.Method x : atm.getClass().getMethods()) {
                if (x.getName().equals("startActivityAsCaller")
                        && x.getParameterTypes().length == 12) {
                    m = x;
                    break;
                }
            }
            if (m == null) {
                logf("asc no method");
                return -96;
            }
            Object res = m.invoke(atm,
                    null,                    // IApplicationThread caller
                    "android",               // callingPackage（uid 1000 自己）
                    fi,
                    fi.getType(),
                    tok,                     // resultTo
                    null,                    // resultWho
                    0,                       // requestCode
                    fi.getFlags(),
                    null,                    // ProfilerInfo
                    null,                    // Bundle options
                    true,                    // ignoreTargetSecurity
                    user);
            logf("asc call ok res=" + res);
            return res instanceof Integer ? (Integer) res : -98;
        } catch (Throwable t) {
            logf("asc fail " + android.util.Log.getStackTraceString(t));
            return -97;
        }
    }

    /**
     * content://media/... → file:///storage/emulated/0/...（按 MediaStore 查真实路径）。
     * 只为绕过 Android 15 对 EXTRA_STREAM 的 "调用方必须持权" 校验。
     */
    static Uri toFileUri(Object sysCtx, android.os.Parcelable p) {
        try {
            if (!(p instanceof Uri)) return null;
            Uri u = (Uri) p;
            String s = u.toString();
            if (s.startsWith("file://")) return u;
            if (!s.startsWith("content://media/")) return null;
            String id = u.getLastPathSegment();
            if (id == null) return null;
            android.content.ContentResolver cr =
                    (android.content.ContentResolver) XposedHelpers.callMethod(sysCtx, "getContentResolver");
            android.database.Cursor c = (android.database.Cursor) XposedHelpers.callMethod(cr, "query",
                    u, new String[]{"_data"}, null, null, null);
            try {
                if (c != null && c.moveToFirst()) {
                    String path = c.getString(0);
                    if (path != null && path.length() > 0) {
                        return Uri.parse("file://" + path);
                    }
                }
            } finally {
                if (c != null) c.close();
            }
        } catch (Throwable t) {
            logf("toFileUri fail " + rootCause(t));
        }
        return null;
    }

    /** 用 IActivityManager 强制停掉某个 user 下的包（用来收拾 u999 里被分身策略拉起的实例） */
    static void forceStopPkgInUser(String pkg, int user) {
        try {
            Object am = Class.forName("android.app.ActivityManager")
                    .getMethod("getService").invoke(null);
            java.lang.reflect.Method m = null;
            for (java.lang.reflect.Method x : am.getClass().getMethods()) {
                if (x.getName().equals("forceStopPackage")
                        && x.getParameterTypes().length == 2
                        && x.getParameterTypes()[0] == String.class
                        && x.getParameterTypes()[1] == int.class) {
                    m = x;
                    break;
                }
            }
            if (m == null) {
                logf("fsp no method");
                return;
            }
            m.invoke(am, pkg, user);
            logf("fsp ok " + pkg + " u" + user);
        } catch (Throwable t) {
            logf("fsp fail " + rootCause(t));
        }
    }

    /** 临时诊断：把 ATMS / IActivityTaskManager 里带 Caller 的启动方法签名落到文件日志。 */
    static void dumpCallerSigs(ClassLoader cl) {
        try {
            Class<?> atms = Class.forName("com.android.server.wm.ActivityTaskManagerService", false, cl);
            for (java.lang.reflect.Method m : atms.getDeclaredMethods()) {
                if (!m.getName().toLowerCase().contains("caller")) continue;
                StringBuilder sb = new StringBuilder("SIG " + m.getName() + "(");
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(ps[i].getSimpleName());
                }
                sb.append(")");
                logf(sb.toString());
            }
            Class<?> iatm = Class.forName("android.app.IActivityTaskManager", false, cl);
            for (java.lang.reflect.Method m : iatm.getDeclaredMethods()) {
                if (!m.getName().toLowerCase().contains("caller")) continue;
                StringBuilder sb = new StringBuilder("ISIG " + m.getName() + "(");
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(ps[i].getSimpleName());
                }
                sb.append(")");
                logf(sb.toString());
            }
        } catch (Throwable t) {
            logf("SIG fail " + t);
        }
    }

    static void hookSystemServer(ClassLoader cl) {
        SYS_CL = cl;
        hookOplusMultiApp(cl);
        hookPms(cl);
        hookAts(cl);
    }

    static void hookOplusMultiApp(ClassLoader cl) {
        Class<?> svc;
        try {
            svc = XposedHelpers.findClass("com.android.server.am.OplusMultiAppManagerService", cl);
        } catch (Throwable t) {
            log("no OplusMultiAppManagerService: " + t);
            return;
        }
        log("found OplusMultiAppManagerService");

        hookAllByName(svc, "isMultiAppUserId", probe("isMultiAppUserId"));
        hookAllByName(svc, "getCorrectUserId", probe("getCorrectUserId"));
        hookAllByName(svc, "getMultiAppList", probe("getMultiAppList"));

        // 选择器上的"空间名"不是取自 ResolveInfo，而是 Oplus 自己按 userId 算出来的。
        // 找出这类 (int)->String 方法，才能让 10..16 显示成各自的空间名。
        try {
            StringBuilder sb = new StringBuilder("MULTIAPP api:");
            for (java.lang.reflect.Method m : svc.getDeclaredMethods()) {
                String n = m.getName();
                Class<?>[] ps = m.getParameterTypes();
                boolean interesting = n.contains("Name") || n.contains("Label")
                        || n.contains("Alias") || n.contains("Title") || n.contains("Space");
                if (interesting || (ps.length == 1 && ps[0] == int.class
                        && m.getReturnType() == String.class)) {
                    sb.append(' ').append(n).append('(');
                    for (Class<?> pt : ps) sb.append(simple(pt)).append(',');
                    sb.append(")->").append(simple(m.getReturnType()));
                }
            }
            log(sb.toString());
        } catch (Throwable ignored) {
        }

        // ColorOS 会把自己的多开候选裁到"主空间 + 999"，把我们注入的其它空间丢掉。
        // 这里在它的过滤结果上把剩余空间补回去（同 user 的以 ColorOS 自己的条目为准）。
        hookAllByName(svc, "filterResolveInfoForMultiApp", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    List<?> in = p.args.length > 1 ? asList(p.args[1]) : null;
                    Object ret = p.getResult();
                    List<?> out = asList(ret);
                    if (out == null) out = in;      // 有些版本是原地过滤、返回 void
                    if (in == null || out == null) return;

                    Set<Integer> users = new LinkedHashSet<Integer>();
                    for (Object o : out) if (o instanceof ResolveInfo) users.add(userOf((ResolveInfo) o));

                    List<Object> merged = new ArrayList<Object>(out);
                    int add = 0;
                    for (Object o : in) {
                        if (!(o instanceof ResolveInfo)) continue;
                        int u = userOf((ResolveInfo) o);
                        if (users.contains(u)) continue;
                        users.add(u);
                        merged.add(o);
                        add++;
                    }
                    log("filter in=" + in.size() + " out=" + out.size() + " ret="
                            + (ret == null ? "void" : ret.getClass().getSimpleName())
                            + " add=" + add + " -> " + brief(merged));
                    if (add > 0) {
                        if (ret instanceof List) {
                            p.setResult(merged);
                        } else {
                            @SuppressWarnings("unchecked")
                            List<Object> mutable = (List<Object>) in;
                            try {
                                mutable.clear();
                                mutable.addAll(merged);
                            } catch (Throwable ignored) {
                                // 不可变列表：只能放弃
                            }
                        }
                    }
                } catch (Throwable t) {
                    log("filter merge err: " + t);
                }
            }
        });

        // 关键：ColorOS 对"已注册分身"的包会静默把 user 改成 999（微信就是这条），
        // 不弹选择框。这里把这类 intent 改写成 ACTION_CHOOSER，让系统选择器接管。
        hookAllByName(svc, "changeIntentIfNeed", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (GUARD.get() != null) return;
                    Object r = p.getResult();
                    if (!(r instanceof Intent)) return;
                    Intent cur = (Intent) r;
                    if (Intent.ACTION_CHOOSER.equals(cur.getAction())) return;
                    if (cur.hasExtra(FORCE_KEY)) return;
                    if (cur.hasExtra("android.intent.extra.USER")) return;
                    if (!maybeWatchedIntent(cur)) return;

                    boolean ext = isExternalCaller(p.args);
                    int n = ext ? spacesFor(cur) : -1;
                    log("chooser check ext=" + ext + " spaces=" + n + " " + briefIntent(cur));
                    if (!ext || n < 2) return;

                    Intent inner = new Intent(cur);
                    // 此时 intent 已被 AMS 显式化成 cmp=...，不清掉的话 chooser 只看到 1 个目标，
                    // 会直接启动、不显示选择框。清掉后按 scheme/package 重新解析，才能列出各空间候选。
                    if (inner.getDataString() != null || inner.getPackage() != null) {
                        inner.setComponent(null);
                    }
                    inner.putExtra(FORCE_KEY, true);
                    p.setResult(spaceChooserIntent(inner));
                    log("force chooser spaces=" + n + " " + briefIntent(inner));
                } catch (Throwable t) {
                    log("forceChooser err: " + t);
                }
            }
        });
    }

    static void hookPms(ClassLoader cl) {
        final Class<?> engine;
        try {
            engine = XposedHelpers.findClass("com.android.server.pm.ComputerEngine", cl);
        } catch (Throwable t) {
            log("no ComputerEngine: " + t);
            return;
        }

        for (final Method m : engine.getDeclaredMethods()) {
            if (!"queryIntentActivitiesInternal".equals(m.getName())) continue;
            final String sig = sigOf(m);
            if (m.getParameterTypes().length == 4) {
                ENGINE_QIA4 = m;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (GUARD.get() != null) return;
                            if (ENGINE == null) ENGINE = p.thisObject;
                            Intent it = findIntent(p.args);
                            if (it == null) return;
                            if (Intent.ACTION_CHOOSER.equals(it.getAction())) return;
                            if (it.hasExtra(SpaceChooserActivity.EXTRA_NO_INJECT)) return;
                            // 注意：这里不能因为带 FORCE_KEY 就跳过。
                            // 选择器拿到内层 intent 后会按 user=0 重新解析一次，那一次才是
                            // 决定列表里能看到几个空间的关键；FORCE_KEY 只用来阻止二次包装。

                            List<?> res = asList(p.getResult());
                            if (res == null || res.isEmpty()) return;

                            int user = userArg(p, -1);
                            if (user != 0) return;
                            if (!MULTISPACE_INJECT) return;
                            if (!shouldInject(it, res)) return;
                            log("QIA" + sig + " user=" + user + " size=" + res.size()
                                    + " it=" + briefIntent(it));

                            inject(p, it, res);
                        } catch (Throwable t) {
                            log("QIA hook err: " + t);
                        }
                    }
                });
                log("hooked ComputerEngine#queryIntentActivitiesInternal" + sig);
            } catch (Throwable t) {
                log("hook QIA" + sig + " fail: " + t);
            }
        }
    }

    static void hookAts(ClassLoader cl) {
        try {
            Class<?> ats = XposedHelpers.findClass("com.android.server.wm.ActivityTaskSupervisor", cl);
            for (String n : new String[]{"resolveIntent", "resolveActivity"}) {
                hookAllByName(ats, n, probe(n));
            }
        } catch (Throwable t) {
            log("no ActivityTaskSupervisor: " + t);
        }
        try {
            Class<?> atm = XposedHelpers.findClass("com.android.server.wm.ActivityTaskManagerService", cl);
            final XC_MethodHook START_HOOK = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (GUARD.get() != null) return;
                        // === 来自 SpaceChooserActivity 的选择：跨用户启动目标 Activity ===
                        Intent fromChooser = findIntent(p.args);
                        if (fromChooser != null && fromChooser.hasExtra(SpaceChooserActivity.EXTRA_FROM_CHOOSER)) {
                            int targetUser = fromChooser.getIntExtra(SpaceChooserActivity.EXTRA_TARGET_USER, -1);
                            if (targetUser >= 0) {
                                logf("chooser target=" + targetUser + " cuid=" + Binder.getCallingUid());
                                // 注：不要让"主空间"改走系统原生链路（放行选择器自己的 start）。
                                // ColorOS 会把第三方发起的微信启动静默改到 999，实测 13:45 那次
                                // 起来的是 u999 的 ShareImgUI 并立刻被销毁。统一由模块按目标 user 回放；
                                // 分享 URI 已经换成免权限的 ShareProvider，不再依赖跨 user 转授。
                                // 1) 清掉标记，防止后续 hook 再拦截
                                fromChooser.removeExtra(SpaceChooserActivity.EXTRA_FROM_CHOOSER);
                                fromChooser.removeExtra(SpaceChooserActivity.EXTRA_TARGET_USER);
                                fromChooser.removeExtra(SpaceChooserActivity.EXTRA_NO_INJECT);
                                fromChooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                // 2) intent 已带显式 component（由选择器按目标空间解析好）
                                //    但要把调用方的 MULTIPLE_TASK / EXCLUDE_FROM_RECENTS 摘掉：
                                //    相册分享的 SEND 是 0x18880003，带着这两个 flag 回放时目标应用
                                //    每次都被要求开新任务、且不进最近任务，实测 u0 微信起了却不显形
                                //    （task 建了、前台还是相册）；去掉后同一任务被正常带到前台。
                                fromChooser.setFlags((fromChooser.getFlags()
                                        & ~(Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
                                        | Intent.FLAG_ACTIVITY_NEW_TASK);
                                fromChooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                // 3) 用系统上下文以目标 user 启动。
                                //    不要指望改 ATMS.startActivityAsUser 的 int 形参：
                                //    各家 ROM 重载顺序不同，实测改错下标会静默落在 u0。
                                //    ContextImpl#startActivityAsUser(Intent, UserHandle) 的 user 是显式的，
                                //    且系统上下文本身持 INTERACT_ACROSS_USERS。
                                //
                                //    必须"稍后再启动"，不能在本次 start 回调里直接再发一次 start：
                                //    同一次启动事务里再发 start，新任务的转场会被丢掉
                                //    （实测主空间那次：task 建了、WeChat 进程也起来了，但前台仍是相册）。
                                final Intent fi = fromChooser;
                                final int tu = targetUser;
                                p.setResult(Integer.valueOf(0));
                                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                long token2 = Binder.clearCallingIdentity();
                                                try {
                                                    {
                                                        Object ctx = systemContext();
                                                        if (ctx == null) throw new IllegalStateException("no system context");
                                                        Object uh = userHandleOf(tu);
                                                        String tp = targetPkgOf(fi);
                                                        // 主空间（u0）的死结：Android 15 在 ActivityStarter 里会校验
                                                        // 「发起方自己必须持有 EXTRA_STREAM 那个 content:// 的读权限」
                                                        // （enforceRequireContentUriPermissionFromCallerOnIntentExtraStream），
                                                        // 微信 ShareImgUI 拿到图后再转一手时，发起方是 u0 的微信自己，
                                                        // 而 u0 微信拿不到这个媒体 URI 的授权（系统身份不能发放、
                                                        // 以本进程发放又不被认），于是 SecurityException 崩。
                                                        // 其它空间（999/10–16）微信是分身/多开实例，ColorOS 另有授权链，不受影响。
                                                        // 绕过办法：只给 u0 这一单把 EXTRA_STREAM 换成 file:// 真实路径。
                                                        // 微信本来就有读媒体存储的权限，按路径读不经过 UriGrant 校验。
                                                        boolean asFile = false;
                                                        if (tu == 0 && "com.tencent.mm".equals(tp)) {
                                                            Uri fu = toFileUri(systemContext(), fi.getParcelableExtra(Intent.EXTRA_STREAM));
                                                            if (fu != null) {
                                                                fi.putExtra(Intent.EXTRA_STREAM, fu);
                                                                // ClipData 里也带着原来那个 content://，
                                                                // 只改 EXTRA_STREAM 不够（微信还会从 clip 里取，
                                                                // 实测 19:11 仍报同一个 SecurityException）。
                                                                android.content.ClipData cd = fi.getClipData();
                                                                if (cd != null) {
                                                                    android.content.ClipData ncd =
                                                                            new android.content.ClipData(cd.getDescription(),
                                                                                    new android.content.ClipData.Item(fu));
                                                                    fi.setClipData(ncd);
                                                                }
                                                                asFile = true;
                                                                logf("replay file u0 " + fu);
                                                            } else {
                                                                logf("replay file u0 none");
                                                            }
                                                        }
                                                        // 分享图片的读权限：不能靠 uid 1000 去"发放"
                                                        // （UriGrantsManagerService 对 system 身份直接拒绝：
                                                        //  "For security reasons, the system cannot issue a Uri
                                                        //   permission grant"，2026-11-10 实测）。
                                                        // 改成以我们自己的包（=com.crossspace.proto，天生持
                                                        // READ_MEDIA_IMAGES）为 granter 逐条授权；因此先确保
                                                        // 本包在目标空间确实拿到了媒体读权限。
                                                        ensureOwnMediaPerm(tu);
                                                        if (tp != null) grantViaOwnApp(fi, tp, tu);
                                                        // 实测结论（2026-11-10）：
                                                        //  system 身份 grant 被 URG 直接拒绝（"For security reasons...use
                                                        //  startActivityAsCaller() instead"）；
                                                        //  以 root 身份跑 am start --grant-read-uri-permission 能成，
                                                        //  但 system_server 的 mount namespace 里看不到 /system/bin/su
                                                        //  （error=2 No such file or directory），走不通；
                                                        //  真正有效的授权只能来自「本进程」，见 SpaceChooserActivity#forwardGrants
                                                        //  （必须用 application context，Activity context 的授权随 Activity 销毁失效）。
                                                        // 2026-11-10 追加：本进程授权确实进了 UGM（dumpsys 里 UID 10360 已持有
                                                        // 该 media URI 的 UriPermission），但微信 u0 二次 start 仍报
                                                        // "UID 10360 does not have permission"，说明
                                                        // enforceRequireContentUriPermissionFromCallerOnIntentExtraStream
                                                        // 认的不是"UGM 里有没有"，而是"发起方是否就是真实原调用链上的持权者"。
                                                        // 因此改走系统给的官方入口 startActivityAsCaller：
                                                        // 以选择器 Activity 的 token 为 resultTo，ATMS 会用「真正的原始调用方
                                                        // （相册）」身份回放这次启动，URI 授权也就以相册的身份发出。
                                                        int asc = -1;
                                                        if (asFile) {
                                                            // 已经换成 file:// 路径，不需要再借相册身份授权
                                                            XposedHelpers.callMethod(ctx, "startActivityAsUser", fi, uh);
                                                            logf("replay file-start u0 " + briefIntent(fi));
                                                            return;
                                                        }
                                                        if (tp != null) asc = startAsCaller(fi, tp, tu);
                                                        if (asc == 0) {
                                                            logf("replay asc ok user=" + tu + " " + briefIntent(fi));
                                                            if (tu == 0) {
                                                                // asc 会把「以相册身份授权」这件事做对，但 ColorOS 的应用分身
                                                                // 会把这单落到 u999 上（实测 top=u999 SelectConversationUI）。
                                                                // 而且只要 u999 的微信还活着，我们按 u0 再启动也会被并到
                                                                // u999（u0 里根本没建出 ActivityRecord）。所以顺序是：
                                                                // asc 授权 → 杀掉 u999 那份微信 → 再按 u0 启动。
                                                                try {
                                                                    Thread.sleep(1200);
                                                                } catch (Throwable ignored) {
                                                                }
                                                                forceStopPkgInUser("com.tencent.mm", 999);
                                                                try {
                                                                    Thread.sleep(400);
                                                                } catch (Throwable ignored) {
                                                                }
                                                                XposedHelpers.callMethod(ctx, "startActivityAsUser", fi, uh);
                                                                logf("replay asc+u0 " + briefIntent(fi));
                                                                try {
                                                                    Thread.sleep(700);
                                                                } catch (Throwable ignored) {
                                                                }
                                                                Intent again = new Intent(fi);
                                                                again.addFlags(0x10000000);
                                                                XposedHelpers.callMethod(ctx, "startActivityAsUser", again, uh);
                                                                logf("replay asc+u0 retry");
                                                            }
                                                            return;
                                                        }
                                                        logf("replay asc res=" + asc + " user=" + tu);
                                                        XposedHelpers.callMethod(ctx, "startActivityAsUser", fi, uh);
                                                        log("replay ok(sys): user=" + tu + " " + briefIntent(fi));
                                                        logf("replay ok sys user=" + tu + " " + briefIntent(fi));
                                                    }
                                                } catch (Throwable e) {
                                                    log("replay fail: " + rootCause(e));
                                                    logf("replay fail " + rootCause(e));
                                                } finally {
                                                    Binder.restoreCallingIdentity(token2);
                                                }
                                            }
                                        }, 900);
                                return;
                            }
                        }

                        Intent it = findIntent(p.args);
                        if (it == null) return;
                        if (Intent.ACTION_CHOOSER.equals(it.getAction())) return;
                        // 诊断：凡是被"目标应用相关"的启动，先把入口/调用方记下来，
                        // 否则后面一堆早退分支（外部判定、空间数不足）会静默吃掉线索。
                        if (maybeWatchedIntent(it)) {
                            String cp = callerPkgOf(p.args);
                            int cu = Binder.getCallingUid();
                            log("enter " + p.method.getName() + " caller=" + cp + "/" + cu
                                    + " ext=" + isExternalPkg(cp, cu) + " it=" + briefIntent(it));
                        }
                        // 相册/oshare 是历史漏拦点，单独盯一下：它们到底有没有走到 ATMS
                        String cp0 = callerPkgOf(p.args);
                        if (cp0 != null && (cp0.contains("gallery") || cp0.contains("oshare"))) {
                            log("probe " + p.method.getName() + " caller=" + cp0 + " it=" + briefIntent(it));
                        }
                        if (it.hasExtra(FORCE_KEY)) return;
                        if (it.hasExtra(SpaceChooserActivity.EXTRA_NO_INJECT)) return;
                        if (it.hasExtra("android.intent.extra.USER")) return;
                        // 只处理"带 scheme / 带包名"的跨应用调用；
                        // 纯显式 component（微信内部 Activity 之间跳转）不碰，否则会反复包 chooser。
                        boolean hasTarget = it.getPackage() != null || componentPkg(it) != null;
                        boolean share = Intent.ACTION_SEND.equals(it.getAction())
                                || Intent.ACTION_SEND_MULTIPLE.equals(it.getAction())
                                || Intent.ACTION_SENDTO.equals(it.getAction());
                        if (it.getDataString() == null && !hasTarget && !share) return;
                        // 目标应用自己发起的跳转不拦（否则刚拉起的分身里会再叠一层选择器）
                        if (isWatchedCaller(Binder.getCallingUid())) return;
                        if (!maybeWatchedIntent(it)) return;
                        boolean ext = isExternalStart(p.args);
                        if (!ext) {
                            log("skip not-external caller=" + callerPkgOf(p.args) + "/"
                                    + Binder.getCallingUid() + " it=" + briefIntent(it));
                            return;
                        }

                        int n = spacesFor(it);
                        if (n < 2) {
                            log("start: spaces=" + n + " skip " + briefIntent(it));
                            return;
                        }

                        Intent inner = new Intent(it);
                        // AMS 这里拿到的还是原始 intent，但可能已带 component；
                        // 清掉才能按 scheme/package 重新解析出各空间候选。
                        if (inner.getDataString() != null || inner.getPackage() != null) {
                            inner.setComponent(null);
                        }
                        inner.putExtra(FORCE_KEY, true);
                        Intent chooser = spaceChooserIntent(inner);
                        // 不要把原始 intent 的 flags 抄给选择器：相册分享的 SEND 带
                        // MULTIPLE_TASK|EXCLUDE_FROM_RECENTS(0x18880003)，ColorOS 会据此
                        // 把我们的选择器渲染成右上角小窗，点按落在窗口外、看起来"点了没反应"。
                        chooser.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

                        int idx = indexOfIntent(p.args);
                        if (idx >= 0) {
                            p.args[idx] = chooser;
                            // 顺带清掉调用方带来的 ActivityOptions（相册/分享面板会塞
                            // ColorOS 的小窗窗口模式），否则选择器被塞进小窗、点不中。
                            for (int k = 0; k < p.args.length; k++) {
                                Object a = p.args[k];
                                if (a == null) continue;
                                String cn = a.getClass().getName();
                                if (cn.equals("android.app.ActivityOptions")) {
                                    p.args[k] = null;
                                    logf("clear option idx=" + k + " type=" + cn);
                                }
                            }
                            log("start -> chooser spaces=" + n + " " + briefIntent(inner));
                        }
                    } catch (Throwable t) {
                        log("start force err: " + t);
                    }
                }
            };
            // 相册等第三方应用点分享面板里的「微信」时，走的不是 AOSP 的 startActivityAsUser，
            // 而是 ColorOS 自己加的重载（startActivityAsUserWithFeature / ...AndWait 之类）。
            // 所以这里不只挂三个固定名字，而是把 ATMS 上所有 startActivity* 方法全挂上。
            int hooked = 0;
            for (java.lang.reflect.Method m : atm.getDeclaredMethods()) {
                String n = m.getName();
                if (!n.startsWith("startActivity")) continue;
                try {
                    XposedBridge.hookMethod(m, START_HOOK);
                    hooked++;
                    log("hooked ATMS#" + n + "/" + m.getParameterTypes().length);
                } catch (Throwable t) {
                    log("hook ATMS#" + n + " fail: " + t);
                }
            }
            log("hooked ATMS total=" + hooked);
        } catch (Throwable t) {
            log("no ActivityTaskManagerService: " + t);
        }
    }

    static int indexOfIntent(Object[] args) {
        if (args == null) return -1;
        for (int i = 0; i < args.length; i++) if (args[i] instanceof Intent) return i;
        return -1;
    }

    /**
     * 显式指向系统的 IntentResolver 选择器。
     * 本机还有第三方应用（top.bienvenido.saas.i18n）注册了 ACTION_CHOOSER，
     * 隐式解析会变成多候选，系统转而启动 android/com.android.internal.app.ResolverActivity，
     * 那个实现对"同一组件多 user"的排序比较器不满足传递性，直接崩溃、面板不出来。
     */
    static Intent chooserFor(Intent inner) {
        Intent chooser = Intent.createChooser(inner, null);
        try {
            chooser.setComponent(new ComponentName("com.android.intentresolver",
                    "com.android.intentresolver.ChooserActivityLauncher"));
        } catch (Throwable t) {
            log("chooserFor err: " + t);
        }
        return chooser;
    }

    /**
     * 用自建的 SpaceChooserActivity 替换系统选择器。
     *
     * 为什么不用系统 chooser：
     *  - ColorOS 用 getMultiAppAlias(pkg) 统一生成别名（如「<用户名>.999」），
     *    会覆盖我们写在 ResolveInfo.nonLocalizedLabel 上的「微信 · 空间12」，10..16 无法区分；
     *  - 系统 ResolverListController 对「同一组件多 user」的排序比较器不满足传递性，会抛异常崩掉。
     * 自建 UI 后，标签完全由我们控制，也不依赖 IntentResolver。
     */
    static Intent spaceChooserIntent(Intent orig) {
        List<String> pkgs = new ArrayList<String>();
        List<String> names = new ArrayList<String>();
        List<Integer> users = new ArrayList<Integer>();
        List<String> labels = new ArrayList<String>();

        GUARD.set(Boolean.TRUE);
        try {
            for (int u : ALL_USERS) {
                List<?> rl = queryAsUser(orig, u);
                if (rl == null) continue;
                for (Object o : rl) {
                    if (!(o instanceof ResolveInfo)) continue;
                    String pkg = pkgOf(o);
                    if (!isWatchedPkg(pkg)) continue;
                    String name = nameOf(o);
                    if (name == null) continue;
                    // 按 (pkg, user) 去重：一个应用在一个空间只给一条候选。
                    // 分享（ACTION_SEND）时同一个包在同一空间能解析出多个入口
                    // （微信 ShareImgUI / ShareToTimeLineUI / WXEntryActivity…），
                    // 按 (pkg,name,user) 去重会列出 5 条一模一样的「微信 · 主空间」。
                    boolean dup = false;
                    for (int i = 0; i < pkgs.size(); i++) {
                        if (pkgs.get(i).equals(pkg) && users.get(i).intValue() == u) {
                            dup = true;
                            break;
                        }
                    }
                    if (dup) continue;
                    pkgs.add(pkg);
                    names.add(name);
                    users.add(Integer.valueOf(u));
                    labels.add(labelOf(pkg) + " · " + (u == 0 ? "主空间" : "空间" + u));
                }
            }
        } finally {
            GUARD.remove();
        }

        if (pkgs.isEmpty()) {
            log("spaceChooserIntent: no candidate, fallback " + briefIntent(orig));
            return chooserFor(orig);
        }

        Intent chooser = new Intent();
        chooser.setComponent(new ComponentName("com.crossspace.proto",
                "com.crossspace.proto.SpaceChooserActivity"));
        Intent stored = new Intent(orig);
        wrapShareUris(stored);
        chooser.putExtra(SpaceChooserActivity.EXTRA_ORIG, stored);
        chooser.putExtra(SpaceChooserActivity.EXTRA_PKGS, pkgs.toArray(new String[0]));
        chooser.putExtra(SpaceChooserActivity.EXTRA_NAMES, names.toArray(new String[0]));
        chooser.putExtra(SpaceChooserActivity.EXTRA_USERS, toIntArray(users));
        chooser.putExtra(SpaceChooserActivity.EXTRA_LABELS, labels.toArray(new String[0]));
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // 让调用方（相册等）在拉起选择器时，把它手里那个 content:// 图片的读权限一起转授给
        // 选择器：选"主空间"时不走我们的跨 user 回放，而是让选择器自己正常 startActivity，
        // 由框架继续把权限从选择器转授给目标应用（这条链路是系统自带的，权限没问题）。
        if (orig.getClipData() != null || orig.getData() != null
                || orig.getParcelableExtra(Intent.EXTRA_STREAM) != null) {
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        log("spaceChooserIntent: candidates=" + pkgs.size() + " " + labels);
        return chooser;
    }

    static int[] toIntArray(List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i).intValue();
        return a;
    }

    static boolean isExternalStart(Object[] args) {
        int uid = Binder.getCallingUid();
        if (uid == 0 || uid == 1000) return false;   // root / system
        String pkg = null;
        if (args != null) {
            for (Object a : args) {
                if (a instanceof String) {
                    String s = (String) a;
                    if (s != null && s.indexOf('.') > 0) { pkg = s; break; }
                }
            }
        }
        return isExternalPkg(pkg, uid);
    }

    static boolean isExternalPkg(String pkg, int uid) {
        if (pkg == null) return uid >= 10000 || uid == 2000;
        return !isSystemSurface(pkg);
    }

    /**
     * 调用方是不是"被跨空间的那个应用自己"。
     * 微信/支付宝起来后会继续发自己的深链跳转（WXCustomSchemeEntryActivity → 主界面等），
     * 如果这些也从外部规则走一遍，会在刚拉起的空间上再叠一个选择器，表现为"选了却退不出去"。
     * 用 appId 比较可以同时覆盖 u0 / 999 / 10-30 里的同一应用。
     */
    static boolean isWatchedCaller(int uid) {
        int appId = uid % 100000;   // PER_USER_RANGE
        for (String p : WATCH_PKGS) {
            int pkgUid = packageUid(p);
            if (pkgUid > 0 && (pkgUid % 100000) == appId) return true;
        }
        return false;
    }

    static final Map<String, Integer> PKG_UID = new ConcurrentHashMap<String, Integer>();

    static int packageUid(String pkg) {
        Integer c = PKG_UID.get(pkg);
        if (c != null) return c.intValue();
        try {
            Object ctx = systemContext();
            if (ctx == null) return -1;
            Object pm = XposedHelpers.callMethod(ctx, "getPackageManager");
            Object uid = XposedHelpers.callMethod(pm, "getPackageUid", pkg, Integer.valueOf(0));
            int v = ((Integer) uid).intValue();
            PKG_UID.put(pkg, Integer.valueOf(v));
            return v;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- 注入

    /**
     * 只在"这次解析确实指向微信/支付宝"时注入。
     * 排除桌面/应用列表这类 ACTION_MAIN 全量查询（结果里会顺带包含微信，不能算）。
     */
    /**
     * 是否往系统/第三方的候选列表里追加其它空间的条目。
     *
     * 关掉的原因：改自建选择器后，这条路只会让第三方分享面板（例如相册）列出
     * 一串重复的「微信 · 空间N」——每个空间的每个分享入口都列一遍，而且点哪条
     * 都由那个面板自己决定 user（相册就直接落到当前 user 999），根本不经我们的选择器。
     * 现在统一由 ATMS hook 接管：面板里只出现原生的「微信」一项，点它再弹我们的选择器。
     */
    static final boolean MULTISPACE_INJECT = false;

    static boolean shouldInject(Intent it, List<?> res) {
        String action = it.getAction();
        if (action == null) return false;
        if (Intent.ACTION_MAIN.equals(action)) return false;
        if (Intent.ACTION_CHOOSER.equals(action)) return false;

        ComponentName cn = it.getComponent();
        if (cn != null) return isWatchedPkg(cn.getPackageName());
        if (it.getPackage() != null) return isWatchedPkg(it.getPackage());

        // 有 data 的是"按 scheme 定向"的调用（weixin://、alipays://、https 等）
        if (it.getDataString() != null) return containsWatched(res);

        // 无 data 的分享/发送：结果里必须有微信/支付宝
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)
                || Intent.ACTION_SENDTO.equals(action) || Intent.ACTION_VIEW.equals(action)) {
            return containsWatched(res);
        }
        return false;
    }

    static boolean containsWatched(List<?> res) {
        if (res == null) return false;
        for (Object o : res) if (isWatchedPkg(pkgOf(o))) return true;
        return false;
    }

    /**
     * 把同一 intent 在其它空间解析到的同类 Activity 追加进候选。
     * 目标包由"本次解析结果里出现的 watched 包"决定，不看 scheme。
     */
    static void inject(MethodHookParam p, Intent it, List<?> orig) {
        Set<String> targets = new LinkedHashSet<String>();
        for (Object o : orig) {
            String pkg = pkgOf(o);
            if (isWatchedPkg(pkg)) targets.add(pkg);
        }
        if (targets.isEmpty()) {
            ComponentName cn = it.getComponent();
            if (cn != null && isWatchedPkg(cn.getPackageName())) targets.add(cn.getPackageName());
            else if (isWatchedPkg(it.getPackage())) targets.add(it.getPackage());
        }
        if (targets.isEmpty()) return;

        List<Object> cur = new ArrayList<Object>();
        for (Object o : orig) {
            if (!(o instanceof ResolveInfo)) { cur.add(o); continue; }
            String pkg = pkgOf(o);
            if (!isWatchedPkg(pkg)) { cur.add(o); continue; }
            // 主空间条目也重命名，保证选择框里每一项都带空间后缀
            ResolveInfo copy = new ResolveInfo((ResolveInfo) o);
            stampUser(copy, userOf((ResolveInfo) o));
            cur.add(copy);
        }
        GUARD.set(Boolean.TRUE);
        int added = 0;
        try {
            for (String pkg : targets) {
                for (int u : SPACE_USERS) {
                    List<?> r = queryAsUser(it, u);
                    if (r == null || r.isEmpty()) continue;
                    for (Object o : r) {
                        String rp = pkgOf(o);
                        if (!pkg.equals(rp)) continue;
                        String name = nameOf(o);
                        if (hasEntry(cur, pkg, name, u)) continue;

                        ResolveInfo copy = new ResolveInfo((ResolveInfo) o);
                        stampUser(copy, u);
                        cur.add(copy);
                        INJECTED.put(copy, u);
                        added++;
                        log("inject + " + pkg + "/" + name + " user=" + u
                                + " actual=" + userOf((ResolveInfo) o)
                                + " label=" + copy.nonLocalizedLabel);
                    }
                }
            }
            if (added > 0) {
                p.setResult(cur);
                log("inject done, total=" + cur.size() + " " + brief(cur));
            }
        } catch (Throwable t) {
            log("inject err: " + rootCause(t));
        } finally {
            GUARD.remove();
        }
    }

    /** 目标包名：优先显式 component，其次 intent 里的 package */
    static String targetPkgOf(Intent it) {
        ComponentName c = it.getComponent();
        if (c != null) return c.getPackageName();
        return it.getPackage();
    }

    /**
     * 日志同时落盘。/data/system 只有 system_server(uid 1000) 可写，logcat 缓冲区
     * 会被别家进程刷爆（微信崩溃/strictmode 一秒几十行），拉日志时经常已经轮转掉了。
     */
    static void logf(String s) {
        String line = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS")
                .format(new java.util.Date()) + " " + s + "\n";
        String[] paths = new String[]{"/data/system/crossspace.log", "/data/local/tmp/crossspace.log"};
        for (String path : paths) {
            try {
                java.io.FileOutputStream fo = new java.io.FileOutputStream(path, true);
                fo.write(line.getBytes("UTF-8"));
                fo.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 把分享图片等 content:// URI 的读权限授予目标包（在目标 user 的上下文里）。
     * 不做这一步，跨 user 回放时目标进程读图会 SecurityException（实测 u0 微信直接崩）。
     */
    static void grantClipUris(Object sysCtx, Intent it, String targetPkg, int user) {
        try {
            logf("grantCheck pkg=" + targetPkg + " user=" + user
                    + " flags=0x" + Integer.toHexString(it.getFlags())
                    + " clip=" + (it.getClipData() != null ? it.getClipData().getItemCount() : 0)
                    + " data=" + it.getData());
            if (targetPkg == null) return;
            if ((it.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) return;
            java.util.ArrayList<Uri> clipList = new java.util.ArrayList<Uri>();
            java.util.ArrayList<Uri> uris = new java.util.ArrayList<Uri>();
            ClipData cd = it.getClipData();
            boolean changed = false;
            if (cd != null) {
                for (int i = 0; i < cd.getItemCount(); i++) {
                    Uri u = cd.getItemAt(i).getUri();
                    if (u == null) continue;
                    Uri nu = normalizeMediaUri(u);
                    if (!nu.equals(u)) changed = true;
                    clipList.add(nu);
                    uris.add(nu);
                }
            }
            if (it.getData() != null) {
                Uri d = it.getData();
                Uri nd = normalizeMediaUri(d);
                if (!nd.equals(d)) changed = true;
                uris.add(nd);
            }
            if (uris.isEmpty()) return;
            Object ctxU = XposedHelpers.callMethod(sysCtx, "createContextAsUser",
                    userHandleOf(user), Integer.valueOf(0));
            for (Uri u : uris) {
                if (!"content".equals(u.getScheme())) continue;
                XposedHelpers.callMethod(ctxU, "grantUriPermission", targetPkg, u,
                        Integer.valueOf(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            }
            log("grant uri -> " + targetPkg + " u" + user + " n=" + uris.size());
            logf("grant uri " + targetPkg + " u" + user + " n=" + uris.size());
        } catch (Throwable t) {
            log("grant fail: " + rootCause(t));
            logf("grant fail " + rootCause(t));
        }
    }

    /** content://media/external_primary/xxx -> content://media/external/xxx */
    static Uri normalizeMediaUri(Uri u) {
        try {
            if (u == null) return null;
            if (!"media".equals(u.getAuthority())) return u;
            String p = u.getPath();
            if (p == null || !p.startsWith("/external_primary/")) return u;
            return Uri.parse("content://media/external/" + p.substring("/external_primary/".length()));
        } catch (Throwable t) {
            return u;
        }
    }

    /**
     * 历史方案：把 content:// URI 换成自己包里的 exported 只读 provider URI。
     *
     * 2026-11-10 实测否决：Android 11+ 的"包可见性"会挡掉第三方应用对我们 provider 的
     * 解析——微信（targetSdk 30+、没有声明 <queries> 指向我们）在 u0 和 u12 里都是
     * `Failed to find provider info for com.crossspace.proto.share`，
     * AMS 里连 provider 记录都不会建；于是 ShareImgUI 读不到图，起来几百毫秒就自己关掉。
     * 因此现在不再包装，直接透传原始 URI，靠 replay 前的 grantViaOwnApp() 逐条授权。
     */
    static Uri wrap1(Uri u, String type) {
        return u;   // 已停用 provider 包装，见上面的说明
    }

    /** 一次性收集 intent 里所有 content:// URI（data / EXTRA_STREAM / ClipData）。 */
    static java.util.List<Uri> collectUris(Intent it) {
        java.util.ArrayList<Uri> out = new java.util.ArrayList<Uri>();
        if (it == null) return out;
        if (it.getData() != null) out.add(it.getData());
        Object stream = it.getParcelableExtra(Intent.EXTRA_STREAM);
        if (stream instanceof Uri) out.add((Uri) stream);
        ClipData cd = it.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i++) {
                Uri u = cd.getItemAt(i).getUri();
                if (u != null) out.add(u);
            }
        }
        return out;
    }

    /**
     * 以 root 身份 `am start --grant-read-uri-permission` 启动目标 Activity。
     *
     * 只有这样才能让"有权读相册的一方"（shell/root）当 granter，把该 content:// 的
     * 读权限真正授给目标应用；system(1000) 身份去 grant 会被 UriGrantsManagerService
     * 直接拒绝（"For security reasons, the system cannot issue a Uri permission grant"），
     * 我们的包自己也不持有这条 URI 的授权，所以 forwardGrants 同样无效。
     */
    static boolean rootAmStart(Intent it, Uri uri, int user) {
        try {
            String action = it.getAction() == null ? "android.intent.action.SEND" : it.getAction();
            String type = it.getType();
            String comp = it.getComponent().flattenToShortString();
            StringBuilder sb = new StringBuilder();
            sb.append("/system/bin/am start --user ").append(user)
              .append(" --grant-read-uri-permission")
              .append(" -a ").append(shellQuote(action));
            if (type != null) sb.append(" -t ").append(shellQuote(type));
            sb.append(" -n ").append(shellQuote(comp));
            sb.append(" -f ").append(Intent.FLAG_ACTIVITY_NEW_TASK);
            sb.append(" --eu android.intent.extra.STREAM ").append(shellQuote(uri.toString()));
            String cmd = sb.toString();
            log("root am: " + cmd);
            Process p2 = new ProcessBuilder("/system/bin/su", "-c", cmd)
                    .redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p2.getInputStream()));
            String line, last = "";
            while ((line = r.readLine()) != null) last = line;
            r.close();
            int code = p2.waitFor();
            log("root am done code=" + code + " out=" + last);
            logf("root am code=" + code + " out=" + last);
            return code == 0;
        } catch (Throwable e) {
            log("root am fail: " + rootCause(e));
            logf("root am fail " + rootCause(e));
            return false;
        }
    }

    static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * 确保本模块包在指定空间持有媒体读权限。
     *
     * 目的只有一个：让我们有资格把分享图片的 content:// URI 转授给目标应用
     * （uid 1000 去 grant 会被 UriGrantsManagerService 直接拒绝，见 replay 处注释）。
     * 用 system 身份调 IPackageManager#grantRuntimePermission，本包是自己的，
     * 不会动到第三方应用的权限。
     */
    static void ensureOwnMediaPerm(int user) {
        try {
            Object ipm = Class.forName("android.app.ActivityThread")
                    .getMethod("getPackageManager").invoke(null);
            String[] perms = {
                    "android.permission.READ_MEDIA_IMAGES",
                    "android.permission.READ_MEDIA_VIDEO",
                    "android.permission.READ_EXTERNAL_STORAGE",
            };
            for (String perm : perms) {
                try {
                    XposedHelpers.callMethod(ipm, "grantRuntimePermission",
                            "com.crossspace.proto", perm, Integer.valueOf(user));
                } catch (Throwable ignore) {
                    // 权限已授予 / 该空间没有这个权限，都不影响主流程
                }
            }
            logf("own perm ok u=" + user);
        } catch (Throwable t) {
            logf("own perm fail " + rootCause(t));
        }
    }

    /** 以本模块自己的包身份，把分享 URI 的读权限逐条授给目标应用（同一空间内有效）。 */
    static void grantViaOwnApp(Intent it, String pkg, int user) {
        try {
            Object ctx = systemContext();
            if (ctx == null) return;
            Object uh = userHandleOf(user);
            Object octx = XposedHelpers.callMethod(ctx, "createPackageContextAsUser",
                    "com.crossspace.proto", Integer.valueOf(0), uh);
            for (Uri u : collectUris(it)) {
                try {
                    XposedHelpers.callMethod(octx, "grantUriPermission", pkg, u,
                            Integer.valueOf(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                    logf("owngrant " + pkg + " u" + user + " " + u);
                } catch (Throwable t) {
                    logf("owngrant fail " + rootCause(t));
                }
            }
        } catch (Throwable t) {
            logf("owngrant fail ctx " + rootCause(t));
        }
    }

    static void wrapShareUris(Intent it) {
        try {
            Uri data = it.getData();
            Object stream = it.getParcelableExtra(Intent.EXTRA_STREAM);
            Uri streamUri = (stream instanceof Uri) ? (Uri) stream : null;
            ClipData cd = it.getClipData();
            if (data == null && streamUri == null && cd == null) return;
            String type = it.getType();
            if (cd != null && cd.getItemCount() > 0) {
                java.util.ArrayList<Uri> wrapped = new java.util.ArrayList<Uri>();
                for (int i = 0; i < cd.getItemCount(); i++) {
                    Uri u = cd.getItemAt(i).getUri();
                    if (u == null) continue;
                    wrapped.add(wrap1(u, type));
                }
                if (!wrapped.isEmpty()) {
                    ClipData nc = new ClipData(cd.getDescription(), new ClipData.Item(wrapped.get(0)));
                    for (int i = 1; i < wrapped.size(); i++) nc.addItem(new ClipData.Item(wrapped.get(i)));
                    it.setClipData(nc);
                    it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            }
            if (streamUri != null) {
                Uri w = wrap1(streamUri, type);
                if (it.getClipData() == null) {
                    it.setClipData(new ClipData(ClipData.newRawUri("", w)));
                    it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                it.putExtra(Intent.EXTRA_STREAM, w);
            }
            logf("uri wrap clip=" + String.valueOf(it.getClipData())
                    + " stream=" + String.valueOf(it.getParcelableExtra(Intent.EXTRA_STREAM)));
        } catch (Throwable t) {
            logf("uri wrap fail " + rootCause(t));
        }
    }

    /** 用 system 身份、指定 user 重新查一次（复用同一个 ComputerEngine 实例） */
    static List<?> queryAsUser(Intent it, int user) {
        Method m = ENGINE_QIA4;
        Object engine = ENGINE;
        if (m == null || engine == null) return null;
        long token = Binder.clearCallingIdentity();
        try {
            Object r = m.invoke(engine, new Intent(it), it.getType(), 0L, Integer.valueOf(user));
            return asList(r);
        } catch (Throwable t) {
            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /** 该 intent 在多少个空间能解析到 watched 包 */
    static int spacesFor(Intent it) {
        String key = keyOf(it);
        long now = System.currentTimeMillis();
        long[] cache = SPACE_CACHE.get(key);
        if (cache != null && now - cache[0] < CACHE_TTL_MS) return (int) cache[1];

        int n = 0;
        GUARD.set(Boolean.TRUE);
        try {
            for (int u : ALL_USERS) {
                if (spaceHas(it, u)) n++;
            }
        } finally {
            GUARD.remove();
        }
        SPACE_CACHE.put(key, new long[]{now, n});
        if (SPACE_CACHE.size() > 128) SPACE_CACHE.clear();
        return n;
    }

    static final int[] ALL_USERS = buildAllUsers();

    static int[] buildAllUsers() {
        int[] a = new int[SPACE_USERS.length + 1];
        a[0] = 0;
        System.arraycopy(SPACE_USERS, 0, a, 1, SPACE_USERS.length);
        return a;
    }

    static boolean spaceHas(Intent it, int user) {
        List<?> r = queryAsUser(it, user);
        if (r == null) return false;
        for (Object o : r) if (isWatchedPkg(pkgOf(o))) return true;
        return false;
    }

    static String keyOf(Intent i) {
        ComponentName cn = i.getComponent();
        return String.valueOf(i.getAction()) + '|' + i.getDataString() + '|' + i.getPackage()
                + '|' + i.getType() + '|' + (cn == null ? "" : cn.flattenToShortString());
    }

    // ---------------------------------------------------------------- resolver

    /**
     * 选择器自己排序时会崩：注入「同一组件 + 多个 user」的候选后，
     * ResolverListController 的比较器不满足传递性，
     * TimSort 直接抛 IllegalArgumentException，整个 ChooserActivity 进程挂掉、面板不出现。
     * 这里在列表里存在多空间重复项时跳过它自己的排序，保持系统原始顺序。
     */
    static void hookResolverSort(ClassLoader cl) {
        Class<?> c;
        try {
            c = XposedHelpers.findClass("com.android.intentresolver.ResolverListController", cl);
        } catch (Throwable t) {
            log("no intentresolver ResolverListController: " + t);
            return;
        }
        log("found intentresolver.ResolverListController");
        hookAllByName(c, "sort", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    List<?> list = (p.args != null && p.args.length > 0) ? asList(p.args[0]) : null;
                    if (list == null || list.size() < 2) return;
                    log("chooser sort skip, size=" + list.size() + " dup=" + hasMultiUserDup(list));
                    p.setResult(null);
                } catch (Throwable t) {
                    log("chooser sort err: " + t);
                }
            }
        });
    }

    /** 列表里是否有"同一组件出现在多个空间"的候选（我们注入的条目就是这样） */
    static boolean hasMultiUserDup(List<?> list) {
        Map<String, Integer> first = new java.util.HashMap<String, Integer>();
        for (Object o : list) {
            Object ri = riOf(o);
            if (!(ri instanceof ResolveInfo)) continue;
            ResolveInfo r = (ResolveInfo) ri;
            if (r.activityInfo == null) continue;
            String key = r.activityInfo.packageName + '/' + r.activityInfo.name;
            int u = userOf(r);
            Integer prev = first.get(key);
            if (prev == null) first.put(key, u);
            else if (prev.intValue() != u) return true;
        }
        return false;
    }

    static void hookResolver(ClassLoader cl) {
        hookResolverSort(cl);
        String[] classes = {
                "com.android.intentresolver.ChooserActivity",
                "com.android.intentresolver.ResolverActivity",
        };
        for (String cn : classes) {
            try {
                Class<?> c = XposedHelpers.findClass(cn, cl);
                log("found " + cn);
                hookAllByName(c, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Intent i = (Intent) XposedHelpers.callMethod(p.thisObject, "getIntent");
                            log("chooser onCreate " + briefIntent(i));
                            if (i != null) {
                                Intent inner = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
                                log("chooser inner " + briefIntent(inner));
                                android.os.Parcelable[] init =
                                        (android.os.Parcelable[]) i.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS);
                                log("chooser initialIntents=" + (init == null ? 0 : init.length));
                            }
                            dumpAdapter(p.thisObject);
                        } catch (Throwable t) {
                            log("chooser onCreate err: " + t);
                        }
                    }
                });
                hookAllByName(c, "onStart", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        dumpAdapter(p.thisObject);
                    }
                });
                hookAllByName(c, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        dumpAdapter(p.thisObject);
                    }
                });
            } catch (Throwable t) {
                log("resolver class skip " + cn + ": " + t);
            }
        }
        // 选择器最终放进列表的每一项，确认我们注入的空间条目是否真的进去了
        try {
            Class<?> cla = XposedHelpers.findClass("com.android.intentresolver.ChooserListAdapter", cl);
            hookAllByName(cla, "addResolveInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        ResolveInfo ri = null;
                        for (Object a : p.args) {
                            if (a instanceof ResolveInfo) {
                                ri = (ResolveInfo) a;
                                break;
                            }
                            if (a != null && a.getClass().getSimpleName().contains("DisplayResolveInfo")) {
                                Object o = XposedHelpers.getObjectField(a, "ri");
                                if (o instanceof ResolveInfo) {
                                    ri = (ResolveInfo) o;
                                    break;
                                }
                            }
                        }
                        if (ri != null) {
                            log("chooser item " + pkgOf(ri) + "/" + nameOf(ri)
                                    + " u=" + userOf(ri) + " label=" + ri.nonLocalizedLabel);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            log("ChooserListAdapter skip: " + t);
        }

        // ColorOS 选择器判断"某项是不是多开空间"用的是
        // OplusResolverPagerAdapterHelper.isMultiAppResolveInfo(DisplayResolveInfo)。
        // 它只认系统登记过的多开用户（999），所以 10..16 全被当成"主空间"。
        // 这里把我们注入的空间（userHandle 命中）也认成 multiApp，UI 才会显示各自空间名。
        try {
            Class<?> pah = XposedHelpers.findClass(
                    "com.android.intentresolver.widget.OplusResolverPagerAdapterHelper", cl);
            hookAllByName(pah, "isMultiAppResolveInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    Object ri = p.args.length > 0 ? riOf(p.args[0]) : null;
                    int u = ri instanceof ResolveInfo ? userOf((ResolveInfo) ri) : Integer.MIN_VALUE;
                    if (MR_LOGGED < 40) {
                        MR_LOGGED++;
                        log("MR isMultiAppResolveInfo u=" + u + " -> " + p.getResult()
                                + " label=" + (ri instanceof ResolveInfo
                                ? ((ResolveInfo) ri).nonLocalizedLabel : null));
                    }
                    if (Boolean.FALSE.equals(p.getResult()) && inSpaces(u)) {
                        p.setResult(Boolean.TRUE);
                    }
                }
            });
        } catch (Throwable t) {
            log("OplusResolverPagerAdapterHelper skip: " + t);
        }
    }

    static boolean inSpaces(int u) {
        if (u == 0) return false;
        for (int s : SPACE_USERS) if (s == u) return true;
        return false;
    }

    static Object riOf(Object item) {
        if (item instanceof ResolveInfo) return item;
        Class<?> c = item == null ? null : item.getClass();
        while (c != null) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != ResolveInfo.class) continue;
                try {
                    f.setAccessible(true);
                    return f.get(item);
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    static void dumpListAdapter(String tag, Object a) {
        try {
            int n = (Integer) XposedHelpers.callMethod(a, "getCount");
            StringBuilder sb = new StringBuilder("adapter " + tag + " count=" + n);
            for (int i = 0; i < n && i < 40; i++) {
                Object item = XposedHelpers.callMethod(a, "getItem", new Object[]{i});
                Object ri = riOf(item);
                sb.append("\n  [").append(i).append("] ").append(brief(ri));
                if (ri instanceof ResolveInfo) {
                    sb.append(" label=").append(((ResolveInfo) ri).nonLocalizedLabel);
                } else if (item != null && !ITEM_LOGGED) {
                    ITEM_LOGGED = true;
                    sb.append(" itemClass=").append(item.getClass().getName());
                    Class<?> cc = item.getClass();
                    while (cc != null) {
                        for (Field f : cc.getDeclaredFields()) {
                            sb.append(' ').append(f.getName()).append(':').append(simple(f.getType()));
                        }
                        cc = cc.getSuperclass();
                    }
                }
            }
            log(sb.toString());
        } catch (Throwable t) {
            log("dumpListAdapter " + tag + " err: " + t);
        }
    }

    /** 把选择器 adapter 里的条目打出来：确认它拿到的 ResolveInfo 的 user 是否正确 */
    static void dumpAdapter(Object activity) {
        // ColorOS 15: ChooserActivity.mChooserMultiProfilePagerAdapter#getActiveListAdapter()
        try {
            Object pager = XposedHelpers.getObjectField(activity, "mChooserMultiProfilePagerAdapter");
            Object la = XposedHelpers.callMethod(pager, "getActiveListAdapter");
            if (la != null) {
                dumpListAdapter("activeList", la);
                return;
            }
        } catch (Throwable t) {
            log("activeList err: " + t);
        }
        Class<?> c = activity == null ? null : activity.getClass();
        boolean found = false;
        while (c != null) {
            for (Field f : c.getDeclaredFields()) {
                String tn = f.getType().getName();
                if (!tn.contains("Adapter")) continue;
                try {
                    f.setAccessible(true);
                    Object a = f.get(activity);
                    if (a == null) continue;
                    Method gc = a.getClass().getMethod("getCount");
                    int n = (Integer) gc.invoke(a);
                    if (n <= 0 || n > 60) continue;
                    found = true;
                    Method gi = a.getClass().getMethod("getItem", int.class);
                    StringBuilder sb = new StringBuilder("adapter " + f.getName() + " count=" + n);
                    for (int i = 0; i < n; i++) {
                        Object item = gi.invoke(a);
                        Object ri = null;
                        try {
                            ri = XposedHelpers.getObjectField(item, "ri");
                        } catch (Throwable ignored) {
                        }
                        sb.append("\n  [").append(i).append("] ").append(brief(ri));
                        if (ri instanceof ResolveInfo) {
                            sb.append(" label=").append(((ResolveInfo) ri).nonLocalizedLabel);
                        }
                    }
                    log(sb.toString());
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        if (!found && !ADAPTER_LOGGED) {
            ADAPTER_LOGGED = true;
            StringBuilder sb = new StringBuilder("no adapter field on " + activity.getClass().getName() + ":");
            c = activity.getClass();
            while (c != null) {
                for (Field f : c.getDeclaredFields()) {
                    sb.append(' ').append(c.getSimpleName()).append('.').append(f.getName())
                            .append(':').append(simple(f.getType()));
                }
                c = c.getSuperclass();
            }
            log(sb.toString());
        }
    }

    // ---------------------------------------------------------------- 判定

    static boolean isWatchedPkg(String p) {
        if (p == null) return false;
        for (String x : WATCH_PKGS) if (x.equals(p)) return true;
        return false;
    }

    /** 便宜的预判：这次调用是否可能落在微信/支付宝（不做 PMS 查询） */
    static boolean maybeWatchedIntent(Intent it) {
        String action = it.getAction();
        if (Intent.ACTION_MAIN.equals(action) || Intent.ACTION_CHOOSER.equals(action)) return false;
        ComponentName cn = it.getComponent();
        if (cn != null) return isWatchedPkg(cn.getPackageName());
        if (isWatchedPkg(it.getPackage())) return true;
        // 分享/发送类没有 data、也没有包名，是否有微信/支付宝由后面的 spacesFor 判定
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)
                || Intent.ACTION_SENDTO.equals(action)) return true;
        return hasWatchedData(it);
    }

    /** 从 ATMS 形参里猜调用方包名：第一个"像包名"的字符串。 */
    static String callerPkgOf(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof String) {
                String s = (String) a;
                if (s.indexOf('.') > 0 && !s.startsWith("android.intent")) return s;
            }
        }
        return null;
    }

    /** 调用方是否外部应用（排除系统自身/桌面/选择器/本模块） */
    static boolean isExternalCaller(Object[] args) {
        String pkg = null;
        int uid = -1;
        for (Object a : args) {
            if (a instanceof String) {
                String s = (String) a;
                if (pkg == null && s.indexOf('.') > 0 && !s.startsWith("android.intent")) pkg = s;
            } else if (a instanceof Integer) {
                int v = (Integer) a;
                if (v >= 1000 && v < 1000000) uid = v;
            }
        }
        if (uid == 1000) return false;                    // system
        if (pkg == null) return uid < 0 || uid >= 10000 || uid == 2000;
        if (isSystemSurface(pkg)) return false;
        return true;
    }

    /**
     * 系统外壳类包，不算"外部应用"。
     * 注意不要用 com.oplus./com.coloros. 前缀一刀切：
     * 相册（com.coloros.gallery3d）也是这个前缀，但它就是最常见的外部调用方，
     * 早期用前缀排除导致相册分享的启动根本没进选择器，被 ColorOS 分身逻辑直接甩到 999。
     */
    static boolean isSystemSurface(String pkg) {
        if (pkg == null) return true;
        if (pkg.equals("android") || pkg.equals("com.crossspace.proto")) return true;
        if (pkg.contains("systemui") || pkg.contains("intentresolver")) return true;
        if (pkg.contains("launcher") || pkg.contains("Launcher")) return true;
        return false;
    }

    /** 探针降噪：只看目标应用相关的调用 */
    static boolean filterCall(String name, Object[] args) {
        if (args == null) return true;
        Intent it = findIntent(args);
        if (it != null) {
            if (Intent.ACTION_CHOOSER.equals(it.getAction())) return false;
            List<?> r = null;
            return isWatchedPkg(it.getPackage()) || isWatchedPkg(componentPkg(it)) || hasWatchedData(it);
        }
        return name.startsWith("isMultiAppUserId") || name.startsWith("getCorrectUserId");
    }

    static boolean hasWatchedData(Intent it) {
        String d = it.getDataString();
        if (d == null) return false;
        String s = d.toLowerCase();
        return s.startsWith("weixin:") || s.startsWith("alipays:") || s.contains("tencent.mm")
                || s.contains("alipay");
    }

    static String componentPkg(Intent it) {
        ComponentName cn = it.getComponent();
        return cn == null ? null : cn.getPackageName();
    }

    // ---------------------------------------------------------------- helpers

    static void hookAllByName(Class<?> c, String name, XC_MethodHook cb) {
        int cnt = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            try {
                XposedBridge.hookMethod(m, cb);
                cnt++;
            } catch (Throwable t) {
                log("hookFail " + c.getSimpleName() + "#" + name + " " + sigOf(m) + " : " + t);
            }
        }
        if (cnt > 0) log("hooked " + c.getSimpleName() + "#" + name + " x" + cnt);
    }

    static XC_MethodHook probe(final String name) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    if (!filterCall(name, p.args)) return;
                    log("-> " + name + sigOf(p.method) + " " + argsToStr(p.args));
                } catch (Throwable ignored) {
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (!filterCall(name, p.args)) return;
                    log("<- " + name + " ret=" + brief(p.getResult()));
                } catch (Throwable ignored) {
                }
            }
        };
    }

    static String pkgOf(Object o) {
        if (!(o instanceof ResolveInfo)) return null;
        ActivityInfo ai = ((ResolveInfo) o).activityInfo;
        return ai == null ? null : ai.packageName;
    }

    static String nameOf(Object o) {
        if (!(o instanceof ResolveInfo)) return null;
        ActivityInfo ai = ((ResolveInfo) o).activityInfo;
        return ai == null ? null : ai.name;
    }

    static boolean hasEntry(List<Object> cur, String pkg, String name, int user) {
        for (Object o : cur) {
            if (!(o instanceof ResolveInfo)) continue;
            if (pkg.equals(pkgOf(o)) && name != null && name.equals(nameOf(o))
                    && userOf((ResolveInfo) o) == user) {
                return true;
            }
        }
        return false;
    }

    static int userOf(ResolveInfo ri) {
        try {
            for (Field f : ResolveInfo.class.getDeclaredFields()) {
                if (!UserHandle.class.equals(f.getType())) continue;
                f.setAccessible(true);
                Object v = f.get(ri);
                if (v == null) return 0;
                Method gid = UserHandle.class.getMethod("getIdentifier");
                return (Integer) gid.invoke(v);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    static void stampUser(ResolveInfo ri, int user) {
        try {
            for (Field f : ResolveInfo.class.getDeclaredFields()) {
                if (!UserHandle.class.equals(f.getType())) continue;
                f.setAccessible(true);
                f.set(ri, userHandleOf(user));
                if (!FIELD_LOGGED) {
                    FIELD_LOGGED = true;
                    log("stamp field=" + f.getName() + " static="
                            + java.lang.reflect.Modifier.isStatic(f.getModifiers()));
                }
                break;
            }
            // 让选择框里能区分空间：微信 / 微信 · 空间15
            // 实验：先不改标签，验证 AOSP 分享面板排序崩溃是否由标签改写引起
            if (!RELABEL) return;
            String pkg = pkgOf(ri);
            try {
                CharSequence text = labelOf(pkg) + " · " + (user == 0 ? "主空间" : "空间" + user);
                ri.nonLocalizedLabel = text;
                ri.labelRes = 0;
                // ColorOS 选择器标题取的是 ActivityInfo/ApplicationInfo 的 label，
                // 只改 ResolveInfo 会被它自己的别名覆盖，这里一并改成带空间名的副本。
                if (ri.activityInfo != null) {
                    ActivityInfo ai = new ActivityInfo(ri.activityInfo);
                    if (ai.applicationInfo != null) {
                        ApplicationInfo app = new ApplicationInfo(ai.applicationInfo);
                        app.nonLocalizedLabel = text;
                        app.labelRes = 0;
                        ai.applicationInfo = app;
                    }
                    ai.nonLocalizedLabel = text;
                    ri.activityInfo = ai;
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            log("stampUser fail: " + t);
        }
    }

    static String labelOf(String pkg) {
        if ("com.tencent.mm".equals(pkg)) return "微信";
        if ("com.eg.android.AlipayGphone".equals(pkg)) return "支付宝";
        return String.valueOf(pkg);
    }

    static UserHandle userHandleOf(int uid) throws Exception {
        try {
            Method of = UserHandle.class.getMethod("of", int.class);
            return (UserHandle) of.invoke(null, uid);
        } catch (Throwable ignored) {
        }
        Constructor<UserHandle> c = UserHandle.class.getConstructor(int.class);
        c.setAccessible(true);
        return c.newInstance(uid);
    }

    /** system_server 里的 system Context（uid=1000，带 INTERACT_ACROSS_USERS） */
    static volatile Object SYS_CTX;

    static Object systemContext() {
        Object c = SYS_CTX;
        if (c != null) return c;
        try {
            Class<?> at = XposedHelpers.findClass("android.app.ActivityThread", SYS_CL);
            Object thread = XposedHelpers.callStaticMethod(at, "currentActivityThread");
            if (thread == null) return null;
            c = XposedHelpers.callMethod(thread, "getSystemContext");
            SYS_CTX = c;
            log("system context=" + c);
            return c;
        } catch (Throwable t) {
            log("systemContext fail: " + rootCause(t));
            return null;
        }
    }

    static Intent findIntent(Object[] args) {
        if (args == null) return null;
        for (Object a : args) if (a instanceof Intent) return (Intent) a;
        return null;
    }

    /**
     * 取 userId。这三个重载的形参顺序不同：
     *   (Intent,String,long,int)                                    ->  [3] userId
     *   (Intent,String,long,int,int)                                ->  [3] userId,  [4] filterCallingUid
     *   (Intent,String,long,long,int,int,int,boolean,boolean)       ->  [4] userId,  [5] callingPid, [6] callingUid
     * 共同点：第一个 int 形参一定是 userId。不能盲取最后一个 int（会拿到 callingPid）。
     */
    static int userArg(MethodHookParam p, int def) {
        if (p == null || !(p.method instanceof Method) || p.args == null) return def;
        Class<?>[] ps = ((Method) p.method).getParameterTypes();
        Object[] args = p.args;
        for (int i = 0; i < ps.length && i < args.length; i++) {
            if (ps[i] != int.class) continue;
            return args[i] instanceof Integer ? (Integer) args[i] : def;
        }
        return def;
    }

    @SuppressWarnings("unchecked")
    static List<?> asList(Object o) {
        if (o instanceof List) return (List<?>) o;
        return null;
    }

    static Throwable rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c;
    }

    static String sigOf(Member m) {
        if (m == null || !(m instanceof Method)) return "";
        StringBuilder sb = new StringBuilder("(");
        Class<?>[] ps = ((Method) m).getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(simple(ps[i]));
        }
        return sb.append(')').toString();
    }

    static String simple(Class<?> c) {
        if (c == null) return "?";
        String n = c.getName();
        int i = n.lastIndexOf('.');
        return i < 0 ? n : n.substring(i + 1);
    }

    static String argsToStr(Object[] args) {
        if (args == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(brief(args[i]));
        }
        return sb.append(']').toString();
    }

    static String brief(Object o) {
        if (o == null) return "null";
        if (o instanceof Intent) return briefIntent((Intent) o);
        if (o instanceof ResolveInfo) {
            return "RI{" + pkgOf(o) + "/" + nameOf(o) + ", u=" + userOf((ResolveInfo) o) + "}";
        }
        if (o instanceof List) {
            List<?> l = (List<?>) o;
            StringBuilder sb = new StringBuilder("List[" + l.size() + "]{");
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(brief(l.get(i)));
            }
            return sb.append('}').toString();
        }
        if (o instanceof int[]) return Arrays.toString((int[]) o);
        if (o instanceof ActivityInfo) {
            ActivityInfo ai = (ActivityInfo) o;
            return "AI{" + ai.packageName + "/" + ai.name + "}";
        }
        String s = String.valueOf(o);
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }

    static String briefIntent(Intent i) {
        if (i == null) return "null";
        StringBuilder sb = new StringBuilder("I{");
        sb.append(i.getAction()).append(" data=").append(i.getDataString());
        sb.append(" pkg=").append(i.getPackage());
        ComponentName cn = i.getComponent();
        if (cn != null) sb.append(" cmp=").append(cn.flattenToShortString());
        sb.append(" flg=0x").append(Integer.toHexString(i.getFlags()));
        return sb.append('}').toString();
    }

    static void log(String s) {
        XposedBridge.log(TAG + s);
    }
}
