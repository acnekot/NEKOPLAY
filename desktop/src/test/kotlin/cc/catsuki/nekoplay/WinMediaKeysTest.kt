package cc.catsuki.nekoplay

import kotlin.test.Test
import kotlin.test.assertEquals

/** Win32 MSG 结构布局：x64 上必须是 48 字节，wParam 在偏移 16（读错会把热键 id 取歪） */
class WinMediaKeysTest {
    @Test fun msgLayoutMatchesWin64() {
        val m = WinMediaKeys.Msg()
        assertEquals(48, m.size())
        m.message = 0x0312; m.wParam = 0x1122334455667788L; m.lParam = 7L; m.write()
        assertEquals(0x0312, m.pointer.getInt(8))
        assertEquals(0x1122334455667788L, m.pointer.getLong(16))
        assertEquals(7L, m.pointer.getLong(24))
    }
}
