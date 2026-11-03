// 设备层特权通道（设计文档 §2.2）。
//
// 实现跑在 Shizuku UserService（shell uid）：建屏、取帧、拉起 App 需要
// CAPTURE_VIDEO_OUTPUT / START_ANY_ACTIVITY 一类 shell 级权限，只有这个进程持有。
// 该接口只做"命令转发 + 结果回传"，不含任何业务判断；风险分级与授权在 app 进程的 Guard 里。

package com.neptine.mimir.device.shell;

interface IMimirShell {

    /**
     * 创建私有虚拟屏。
     * 返回 displayId；失败抛异常。
     */
    int createDisplay(String name, int width, int height, int dpi);

    /** 销毁虚拟屏并释放取帧 surface。 */
    void destroyDisplay(int displayId);

    /**
     * 抓取一帧画面，返回 WebP 编码字节；无法取帧时返回 null。
     * timeoutMs 为单次取帧超时。
     */
    @nullable byte[] captureDisplay(int displayId, int timeoutMs);

    /** 把目标 App 拉起并显示到指定副屏；包名无启动入口时返回 false。 */
    boolean launchApp(String packageName, int displayId);

    /**
     * 按系统 Intent action 在指定副屏打开页面（只读复验用）。
     * action 无法解析或启动失败时返回 false。
     */
    boolean launchAction(String action, int displayId);

    /** 移除目标包不在指定显示上的任务，用于清掉跑到主屏的那份。 */
    boolean removeTasksOutside(String packageName, int displayId);

    /**
     * 判断目标包是否在指定显示上存在任务。
     * 无障碍窗口索引偶发退化（副屏窗口读不出来）时，用它判断"应用到底有没有拉起来"。
     */
    boolean hasTaskOnDisplay(String packageName, int displayId);

    /**
     * 把目标包在指定显示上的根任务搬到主屏（并置顶，由用户点击通知触发）。
     * 返回搬走的系统 taskId；没有任务或移动失败返回 -1。
     */
    int moveTaskToMain(String packageName, int displayId);

    /** 把指定系统任务带到前台（结果通知点击后用）。 */
    boolean bringTaskToFront(int taskId);

    /** 向指定副屏注入一次点击。返回值只代表系统接受事件，不代表动作生效。 */
    boolean tap(int displayId, float x, float y, long pressMs);

    /** 沿给定轨迹向指定副屏注入一次滑动。 */
    boolean swipe(int displayId, in float[] xs, in float[] ys, long durationMs);

    /** 向指定副屏注入一次按键。 */
    boolean key(int displayId, int keyCode);

    /**
     * 注入一次带修饰键的按键。
     * metaState 承载 SHIFT 等修饰位，供 ASCII 文本逐字符键入使用。
     */
    boolean keyWithMeta(int displayId, int keyCode, int metaState);

    /**
     * 注入单次按键动作（action 为 KeyEvent.ACTION_DOWN/ACTION_UP）。
     * 逐字符键入需要"按下 SHIFT → 字符 → 抬起 SHIFT"这种序列，
     * 只给 keyCode+metaState 的成对注入无法表达"按住修饰键"。
     */
    boolean keyAction(int displayId, int keyCode, int metaState, int action);

    /**
     * 写入全局剪贴板。
     * 用于中文输入：SET_TEXT 与按键注入都不支持中文，先写剪贴板再注入 KEYCODE_PASTE
     * 是唯一不切输入法的通路。shell uid 不受 Android 10+ 后台剪贴板限制。
     */
    boolean setClipboardText(String text);

    /** 列出有启动入口的第三方应用包名。 */
    String[] launchablePackages();

    /** 启用或停用某个输入法。 */
    boolean setImeEnabled(String imeId, boolean enabled);

    /** 把某个输入法切换为系统默认。 */
    boolean setIme(String imeId);

    /** 读取当前系统默认输入法的 id；读取失败返回空串。 */
    String currentImeId();
}
