package android.hardware.input;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.view.InputEvent;

/**
 * android.hardware.input.IInputManager 的编译期桩（framework @hide）。
 *
 * 只声明设备层实际用到的两个注入方法；运行期由 boot classpath 的真实 AIDL 实现接管，
 * asInterface 会返回真正的代理对象。
 */
public interface IInputManager extends IInterface {

    /**
     * 功能：注入一个输入事件
     * 参数：ev 输入事件；mode 注入模式（2 = WAIT_FOR_FINISH）
     * 返回：系统是否接受事件
     */
    boolean injectInputEvent(InputEvent ev, int mode);

    /**
     * 功能：向指定 uid 注入输入事件（API 33+ 的分支）
     * 参数：ev 输入事件；mode 注入模式；targetUid 目标 uid，-1 表示不限定
     * 返回：系统是否接受事件
     */
    boolean injectInputEventToTarget(InputEvent ev, int mode, int targetUid);

    /** 编译期桩：运行期由 framework 的同名内部类覆盖。 */
    abstract class Stub extends Binder implements IInputManager {

        /**
         * 功能：把 binder 转成接口
         * 参数：obj 服务 binder
         * 返回：IInputManager 代理
         */
        public static IInputManager asInterface(IBinder obj) {
            throw new UnsupportedOperationException("compile-time stub");
        }
    }
}
