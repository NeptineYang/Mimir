package com.neptine.mimir.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务性质判定的口径（v0.3.20 交付门禁）。
 *
 * 目标是堵住"没做完也按页面交付"：泛动词不能再算查看类，
 * 查看与写操作同时出现时按写操作处理，购物/结算类保留页面交付。
 */
class TaskNatureTest {

    /** 「打开/找到」+ 没有写操作时算查看类：页面本身就是交付物。 */
    @Test
    fun openAndFindWithoutOperationAreViewTasks() {
        assertTrue(TaskNature.isViewTask("打开设置，找到关于手机页面并停留"))
        assertTrue(TaskNature.isViewTask("打开计算器"))
        assertTrue(TaskNature.isViewTask("打开微信，停留在聊天列表页"))
        assertTrue(TaskNature.isDeliveryPageTask("打开美团，停留在首页"))
    }

    /** 设置里的开关操作仍是操作类，「设置」这个名词本身不算写操作。 */
    @Test
    fun settingsTogglesAreOperations() {
        assertFalse(TaskNature.isViewTask("打开设置，关闭 WLAN"))
        assertFalse(TaskNature.isViewTask("打开设置，把亮度调低"))
        assertFalse(TaskNature.isViewTask("打开设置，修改语言"))
    }

    /** 明确搜索/查看且没有写操作，仍是查看类。 */
    @Test
    fun explicitSearchAndViewStayViewTasks() {
        assertTrue(TaskNature.isViewTask("打开B站，搜索猫咪，停留在搜索结果页"))
        assertTrue(TaskNature.isViewTask("打开小红书，搜索咖啡"))
        assertTrue(TaskNature.isViewTask("查看微信消息"))
        assertTrue(TaskNature.isViewTask("查看订单"))
        assertTrue(TaskNature.isDeliveryPageTask("打开B站，搜索猫咪，停留在搜索结果页"))
    }

    /** 查看 + 写操作同时出现时按写操作处理，避免"搜索并删除"被当成页面交付。 */
    @Test
    fun operationWinsOverViewHints() {
        assertFalse(TaskNature.isViewTask("搜索文件并删除"))
        assertFalse(TaskNature.isViewTask("查看消息并回复"))
        assertFalse(TaskNature.isDeliveryPageTask("查看消息并回复"))
        assertFalse(TaskNature.isViewTask("打开页面并提交表单"))
    }

    /** 计算任务有数值结果，必须核对，不能按"页面交付"放过。 */
    @Test
    fun calculationIsOperation() {
        assertFalse(TaskNature.isViewTask("打开计算器，计算 12×34，停留在结果页"))
        assertFalse(TaskNature.isDeliveryPageTask("打开计算器，计算 12×34，停留在结果页"))
    }

    /** 发送消息是操作类，必须有完成证据。 */
    @Test
    fun sendMessageIsOperation() {
        assertFalse(TaskNature.isViewTask("给文件传输助手发送消息"))
        assertFalse(TaskNature.isDeliveryPageTask("给文件传输助手发送消息"))
    }

    /** 外卖/购物车/结算保留页面交付口径，用户可以自己完成付款。 */
    @Test
    fun shoppingKeepsDeliveryPage() {
        assertTrue(TaskNature.isDeliveryPageTask("在美团帮我点一份炸鸡"))
        assertFalse(TaskNature.isViewTask("在美团帮我点一份炸鸡"))
        assertTrue(TaskNature.isDeliveryPageTask("把商品加入购物车并停在待支付页"))
    }

    /** 空输入与普通导航不命中查看类。 */
    @Test
    fun emptyAndPlainNavigationAreNotView() {
        assertFalse(TaskNature.isViewTask(""))
        assertFalse(TaskNature.isViewTask("回到上一页"))
    }
}
