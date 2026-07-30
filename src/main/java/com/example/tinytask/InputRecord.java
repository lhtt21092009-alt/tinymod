package com.example.tinytask;

import java.util.ArrayList;
import java.util.List;

public class InputRecord {
    // Phím di chuyển vẫn ghi dạng true/false vì cần trạng thái GIỮ liên tục, không phải sự kiện bấm 1 lần.
    public boolean pressingForward;
    public boolean pressingBack;
    public boolean pressingLeft;
    public boolean pressingRight;
    public boolean jumping;
    public boolean sneaking;

    // Tên (translation key) của TẤT CẢ phím hành động khác đang được giữ ở tick này,
    // ví dụ: "key.attack", "key.use", "key.hotbar.1", "key.drop", "key.swapHands", "key.sprint"...
    // Nhờ ghi theo TÊN thay vì cột true/false cố định, macro có thể ghi lại BẤT KỲ phím nào
    // trong game (kể cả phím do mod khác thêm vào), không chỉ giới hạn 2 nút chuột + 9 phím số.
    public List<String> pressedKeys = new ArrayList<>();

    public InputRecord(boolean forward, boolean back, boolean left, boolean right,
                        boolean jumping, boolean sneaking, List<String> pressedKeys) {
        this.pressingForward = forward;
        this.pressingBack = back;
        this.pressingLeft = left;
        this.pressingRight = right;
        this.jumping = jumping;
        this.sneaking = sneaking;
        if (pressedKeys != null) {
            this.pressedKeys = pressedKeys;
        }
    }
}
