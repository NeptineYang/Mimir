package com.neptine.mimir.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 风险词表的边界（设计文档 §4.2）。
 *
 * 英文 UI 与英文目标 App 是真实存在的输入面：`Delete`、`Pay now`、`Password`
 * 如果只按中文词表匹配，会静默降级成普通动作。这里锁住新增英文词、
 * 大小写与词边界，同时保留中文回归。
 */
class RiskLexiconTest {

    /** 英文的付款动作按 L2B 处理。 */
    @Test
    fun humanOnlyMatchesEnglishPayment() {
        assertEquals("pay now", RiskLexicon.humanOnly("Pay now"))
        assertEquals("confirm payment", RiskLexicon.humanOnly("Confirm Payment"))
    }

    /** 英文密码/验证码类目标按 L2B 处理。 */
    @Test
    fun humanOnlyMatchesEnglishCredentials() {
        assertEquals("password", RiskLexicon.humanOnly("Change Password"))
        assertEquals("verification code", RiskLexicon.humanOnly("Enter verification code"))
        assertEquals("face id", RiskLexicon.humanOnly("Use Face ID"))
    }

    /** 中文 L2B 词表不因大小写/词边界改造而回归。 */
    @Test
    fun humanOnlyKeepsChineseKeywords() {
        assertEquals("立即支付", RiskLexicon.humanOnly("立即支付"))
        assertEquals("支付密码", RiskLexicon.humanOnly("支付密码"))
        assertEquals("转账", RiskLexicon.humanOnly("确认转账"))
    }

    /** 英文的删除/提交类目标按 L2A（可授权）处理；Checkout 不等同于 Pay now。 */
    @Test
    fun authorizableMatchesEnglishWriteActions() {
        assertEquals("delete", RiskLexicon.authorizable("Delete"))
        assertEquals("submit order", RiskLexicon.authorizable("Submit order"))
        assertEquals("checkout", RiskLexicon.authorizable("Checkout"))
        assertEquals("send", RiskLexicon.authorizable("Send"))
    }

    /** 中文 L2A 词表回归。 */
    @Test
    fun authorizableKeepsChineseKeywords() {
        assertEquals("删除", RiskLexicon.authorizable("删除"))
        assertEquals("提交订单", RiskLexicon.authorizable("提交订单"))
        assertEquals("发送", RiskLexicon.authorizable("发送"))
    }

    /** 普通导航/设置文案不能被风险词误伤。 */
    @Test
    fun benignEnglishLabelsAreNotRisk() {
        assertNull(RiskLexicon.humanOnly("Settings"))
        assertNull(RiskLexicon.humanOnly("Open navigation menu"))
        assertNull(RiskLexicon.authorizable("Settings"))
        assertNull(RiskLexicon.authorizable("Open navigation menu"))
        assertNull(RiskLexicon.authorizable("Postal code"))
        assertNull(RiskLexicon.humanOnly("repay"))
    }

    /** 支付标签只认整段；描述性文本不能升级成支付。 */
    @Test
    fun paymentActionLabelEnglish() {
        assertTrue(RiskLexicon.isPaymentActionLabel("Pay now"))
        assertTrue(RiskLexicon.isPaymentActionLabel("Pay $12.50"))
        assertTrue(RiskLexicon.isPaymentActionLabel("Confirm payment"))
        assertFalse(RiskLexicon.isPaymentActionLabel("Payment history"))
        assertFalse(RiskLexicon.isPaymentActionLabel("Pay settings"))
    }

    /** 只剩付款的英文页要能交接；Checkout 仍是可授权的提交步骤。 */
    @Test
    fun paymentOnlyEnglish() {
        assertEquals("pay now", RiskLexicon.paymentOnly("Pay now"))
        assertEquals("cashier", RiskLexicon.paymentOnly("Cashier"))
        assertNull(RiskLexicon.paymentOnly("Checkout"))
    }

    /** 验证页关键词覆盖英文滑块/验证码文案。 */
    @Test
    fun verificationPageEnglish() {
        assertEquals("captcha", RiskLexicon.verificationPage("Please complete the captcha"))
        assertEquals("security check", RiskLexicon.verificationPage("Complete the security check"))
        assertEquals("请完成验证", RiskLexicon.verificationPage("请完成验证后继续"))
    }

    /** 明确要求发消息时才跳过二次授权；资金任务不适用。 */
    @Test
    fun explicitMessageSendEnglish() {
        assertTrue(RiskLexicon.isExplicitMessageSend("send a message to Mom", "CHAT"))
        assertTrue(RiskLexicon.isExplicitMessageSend("reply to the last chat", "REPLY"))
        assertFalse(RiskLexicon.isExplicitMessageSend("transfer money to Bob", "SEND_MESSAGE"))
        assertFalse(RiskLexicon.isExplicitMessageSend("pay the bill", "SEND_MESSAGE"))
    }

    /** 确定/导航按钮按整段匹配，不做子串。 */
    @Test
    fun navigationAndConfirmEnglish() {
        assertTrue(RiskLexicon.isNavigation("Back"))
        assertTrue(RiskLexicon.isNavigation("Close"))
        assertTrue(RiskLexicon.isGenericConfirm("OK"))
        assertTrue(RiskLexicon.isGenericConfirm("Continue"))
        assertFalse(RiskLexicon.isNavigation("Back to home"))
    }

    /** 发送按钮用单词边界匹配，Sender 不算发送。 */
    @Test
    fun sendTargetUsesWordBoundary() {
        assertTrue(RiskLexicon.isSendTarget("Send"))
        assertTrue(RiskLexicon.isSendTarget("Reply"))
        assertFalse(RiskLexicon.isSendTarget("Sender"))
    }
}
