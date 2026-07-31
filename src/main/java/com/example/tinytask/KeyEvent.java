package com.example.tinytask;

/**
 * Một sự kiện bấm/nhả phím duy nhất, giống 1 "track" trên đĩa CD:
 * đọc tuần tự theo thứ tự ghi, tick nào tới thì phát đúng sự kiện đó.
 */
public class KeyEvent {
    public final int tick;        // Số tick tính từ lúc bắt đầu ghi (0 = tick đầu tiên)
    public final String key;      // Translation key của KeyBinding, VD: "key.forward", "key.attack", "key.hotbar.1"
    public final boolean pressed; // true = vừa NHẤN xuống, false = vừa NHẢ ra

    public KeyEvent(int tick, String key, boolean pressed) {
        this.tick = tick;
        this.key = key;
        this.pressed = pressed;
    }
}
