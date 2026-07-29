package com.example.tinytask;

public class InputRecord {
    public static final int HOTBAR_SLOTS = 9; // Phím số 1..9

    public boolean pressingForward;
    public boolean pressingBack;
    public boolean pressingLeft;
    public boolean pressingRight;
    public boolean jumping;
    public boolean sneaking;
    public boolean attacking; // Chuột trái (Attack / Break)
    public boolean usingItem;  // Chuột phải (Use / Place)
    public boolean[] hotbarKeys = new boolean[HOTBAR_SLOTS]; // index 0 = phím "1" ... index 8 = phím "9"

    public InputRecord(boolean forward, boolean back, boolean left, boolean right, 
                       boolean jumping, boolean sneaking, boolean attacking, boolean usingItem,
                       boolean[] hotbarKeys) {
        this.pressingForward = forward;
        this.pressingBack = back;
        this.pressingLeft = left;
        this.pressingRight = right;
        this.jumping = jumping;
        this.sneaking = sneaking;
        this.attacking = attacking;
        this.usingItem = usingItem;
        if (hotbarKeys != null && hotbarKeys.length == HOTBAR_SLOTS) {
            this.hotbarKeys = hotbarKeys;
        }
    }
}
