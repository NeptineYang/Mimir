package android.os;

/**
 * android.os.ServiceManager 的编译期桩（framework @hide）。
 *
 * 运行期不会加载本类：boot classpath 里的 framework 实现优先命中，
 * 本桩只用于让设备层代码通过编译。方法体抛异常，防止误以为它会被执行。
 */
public final class ServiceManager {

    private ServiceManager() {
    }

    /**
     * 功能：按服务名取系统服务 binder
     * 参数：name 服务名，如 Context.INPUT_SERVICE
     * 返回：服务 binder
     */
    public static IBinder getService(String name) {
        throw new UnsupportedOperationException("compile-time stub");
    }
}
