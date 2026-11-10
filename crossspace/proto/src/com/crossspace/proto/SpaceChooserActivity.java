package com.crossspace.proto;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

/**
 * 跨空间选择器 —— 自建 UI。
 *
 * 由 Main 在 system_server 里把原始 intent 包成显式启动本 Activity 的 intent 并预填好候选项，
 * 这样：
 *  1) 每个空间的显示名完全由我们决定（微信 · 主空间 / 微信 · 空间12 ...），
 *     不会被 ColorOS 自己的别名逻辑覆盖；
 *  2) 不依赖系统的 IntentResolver，也就避开了"同组件多 user 排序崩溃"。
 *
 * 点击后，把原始 intent 显式化到目标组件，带上目标空间 userId，交回 system_server，
 * 由那边的钩子以 system 身份重放 startActivityAsUser 完成跨用户启动。
 */
public class SpaceChooserActivity extends Activity {

    public static final String EXTRA_ORIG = "com.crossspace.proto.orig";
    public static final String EXTRA_PKGS = "com.crossspace.proto.pkgs";
    public static final String EXTRA_NAMES = "com.crossspace.proto.names";
    public static final String EXTRA_USERS = "com.crossspace.proto.users";
    public static final String EXTRA_LABELS = "com.crossspace.proto.labels";

    /** 由本 Activity 发起、需要被 system_server 重定向到目标空间的标记 */
    public static final String EXTRA_FROM_CHOOSER = "com.crossspace.proto.from_chooser";
    /** 目标空间 userId */
    public static final String EXTRA_TARGET_USER = "com.crossspace.proto.target_user";
    /** 告诉 PMS 钩子不要对这个 intent 再注入其它空间 */
    public static final String EXTRA_NO_INJECT = "com.crossspace.proto.noinject";
    /** 本选择器 Activity 的 token，给 system_server 侧 startActivityAsCaller 用 */
    public static final String EXTRA_TOKEN = "com.crossspace.proto.token";
    private String[] mPkgs;
    private String[] mNames;
    private String[] mLabels;
    private int[] mUsers;
    private Intent mOrig;
    private ListView mList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("选择打开空间");
        setFinishOnTouchOutside(true);

        mList = new ListView(this);
        mList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                launch(position);
            }
        });
        setContentView(mList);
        bind(getIntent());
    }

    /**
     * launchMode=singleInstance，连续两次支付跳转会被投递到同一个实例。
     * 不处理 onNewIntent 的话第二次会拿着上一次的候选列表，点下去开错空间。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        bind(intent);
    }

    private void bind(Intent i) {
        mOrig = i.getParcelableExtra(EXTRA_ORIG);
        mPkgs = i.getStringArrayExtra(EXTRA_PKGS);
        mNames = i.getStringArrayExtra(EXTRA_NAMES);
        mLabels = i.getStringArrayExtra(EXTRA_LABELS);
        mUsers = i.getIntArrayExtra(EXTRA_USERS);
        if (mOrig == null || mPkgs == null || mUsers == null
                || mPkgs.length != mUsers.length
                || mNames == null || mNames.length != mPkgs.length
                || mLabels == null || mLabels.length != mPkgs.length) {
            finish();
            return;
        }
        mList.setAdapter(new SpaceAdapter());
        android.util.Log.i("CrossSpaceProto", "bind: n=" + mPkgs.length + " first=" + mLabels[0]
                + " last=" + mLabels[mLabels.length - 1] + " orig=" + mOrig);
    }

    private void clog(String s) {
        try {
            java.io.FileOutputStream fo = new java.io.FileOutputStream(
                    new java.io.File(getFilesDir(), "fwd.log"), true);
            fo.write((new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS")
                    .format(new java.util.Date()) + " " + s + "\n").getBytes());
            fo.close();
        } catch (Throwable ignored) {
        }
        android.util.Log.i("CSPChooser", s);
    }

    private void launch(int pos) {
        try {
            Intent t = new Intent(mOrig);
            t.setComponent(new ComponentName(mPkgs[pos], mNames[pos]));
            t.putExtra(EXTRA_FROM_CHOOSER, true);
            t.putExtra(EXTRA_TARGET_USER, mUsers[pos]);
            t.putExtra(EXTRA_NO_INJECT, true);
            t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // 主空间这条要我们自己把 URI 读权限转授出去，原因见 forwardGrants()
            if (mUsers[pos] == (android.os.Process.myUid() / 100000)) {
                forwardGrants(mOrig, mPkgs[pos]);
            }
            android.util.Log.i("CrossSpaceProto", "pick pos=" + pos + " label=" + mLabels[pos]
                    + " user=" + mUsers[pos] + " cmp=" + mPkgs[pos] + "/" + mNames[pos]);
            // 把本 Activity 的 token 一起带过去：system_server 侧要用
            // startActivityAsCaller(..., resultTo=本Activity的token, ...) 回放，
            // 这样 URI 授权才会以「真正的原始调用方（相册）」身份发出。
            try {
                java.lang.reflect.Field f = android.app.Activity.class.getDeclaredField("mToken");
                f.setAccessible(true);
                Object tok = f.get(this);
                if (tok instanceof android.os.IBinder) {
                    android.os.Bundle bt = new android.os.Bundle();
                    bt.putBinder(EXTRA_TOKEN, (android.os.IBinder) tok);
                    t.putExtras(bt);
                    clog("token attached " + tok);
                }
            } catch (Throwable e) {
                clog("token fail " + e);
            }
            startActivity(t);
        } catch (Throwable e) {
            android.util.Log.e("CrossSpaceProto", "launch fail", e);
        }
        // 不立刻 finish：相册这类 startActivityForResult 的调用方一旦拿回结果就立刻回到前台，
        // 会把随后拉起的目标应用压到下面。等目标应用起来后再退出，选择器退出时露出的是目标。
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 1500);
    }

    /**
     * 把分享 URI 的读权限从「本应用」转授给目标应用。
     *
     * 为什么必须在这里做：Android 14+ 的 UriGrantsManagerService 会在
     * enforceRequireContentUriPermissionFromCallerOnIntentExtraStream 里检查
     * 「发起 startActivity 的那个应用自己是否真的持有 EXTRA_STREAM 的读权限」。
     * 主空间微信拿到图片后要再起 MsgRetransmitUI 继续转发，这一步校验不通过就
     * 直接抛 SecurityException 崩掉（日志：UID 10360 does not have permission to content://media/...）。
     * 由 system_server 以 system 身份授是行不通的（URG 明确拒绝：
     * "For security reasons, the system cannot issue a Uri permission grant ... use startActivityAsCaller() instead"），
     * 所以改由本 Activity 所在的进程（自己就持有相册传给我们的那份临时授权）来授。
     * 只对「目标空间 == 本进程所在空间」有意义，别的空间的文件本来就是它们自己的。
     */
    private void forwardGrants(Intent orig, String targetPkg) {
        try {
            java.util.ArrayList<android.net.Uri> us = new java.util.ArrayList<android.net.Uri>();
            if (orig.getData() != null) us.add(orig.getData());
            Object stream = orig.getExtras() == null ? null
                    : orig.getExtras().get(Intent.EXTRA_STREAM);
            if (stream instanceof android.net.Uri) {
                us.add((android.net.Uri) stream);
            } else if (stream instanceof java.util.List) {
                for (Object o : (java.util.List<?>) stream) {
                    if (o instanceof android.net.Uri) us.add((android.net.Uri) o);
                }
            }
            android.content.ClipData cd = orig.getClipData();
            if (cd != null) {
                for (int k = 0; k < cd.getItemCount(); k++) {
                    if (cd.getItemAt(k).getUri() != null) us.add(cd.getItemAt(k).getUri());
                }
            }
            for (android.net.Uri u : us) {
                if (!"content".equals(u.getScheme())) continue;
                try {
                    int selfMode = checkCallingOrSelfUriPermission(
                            u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    clog("fwd self mode=" + selfMode + " " + u);
                    // 必须挂在 application context 的 owner 上：Activity context 的
                    // grantUriPermission 属于该 Activity 的 token，Activity 一 finish
                    // 授权就被回收（微信随后二次 start 时又变成无权限）。
                    getApplicationContext().grantUriPermission(
                            targetPkg, u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    clog("fwd grant " + targetPkg + " " + u);
                    android.util.Log.i("CSPChooser", "fwd grant " + targetPkg + " " + u);
                } catch (Throwable e) {
                    clog("fwd grant fail " + u + " " + e);
                    android.util.Log.e("CSPChooser", "fwd grant fail " + u, e);
                }
            }
        } catch (Throwable e) {
            android.util.Log.e("CrossSpaceProto", "fwd grant outer fail", e);
        }
    }

    private class SpaceAdapter extends BaseAdapter {
        private final Drawable[] mIcons;

        SpaceAdapter() {
            PackageManager pm = getPackageManager();
            mIcons = new Drawable[mPkgs.length];
            for (int k = 0; k < mPkgs.length; k++) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(mPkgs[k], 0);
                    mIcons[k] = ai.loadIcon(pm);
                } catch (Throwable ignored) {
                    mIcons[k] = null;
                }
            }
        }

        @Override public int getCount() { return mPkgs.length; }
        @Override public Object getItem(int p) { return p; }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            ImageView icon;
            TextView label;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                icon = (ImageView) row.getChildAt(0);
                label = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(SpaceChooserActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int pad = dp(16);
                row.setPadding(pad, pad, pad, pad);
                icon = new ImageView(SpaceChooserActivity.this);
                int sz = dp(40);
                row.addView(icon, new LinearLayout.LayoutParams(sz, sz));
                label = new TextView(SpaceChooserActivity.this);
                label.setTextSize(16);
                label.setPadding(dp(14), 0, 0, 0);
                row.addView(label, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (mIcons[position] != null) icon.setImageDrawable(mIcons[position]);
            label.setText(mLabels[position]);
            return row;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
