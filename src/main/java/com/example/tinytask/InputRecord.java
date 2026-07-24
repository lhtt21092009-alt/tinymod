package com.example.tinytask;

public class InputRecord {
    public boolean pressingForward;
    public boolean pressingBack;
    public boolean pressingLeft;
    public boolean pressingRight;
    public boolean jumping;
    public boolean sneaking;
    public boolean attacking; // Chuột trái (Attack / Break)
    public boolean usingItem;  // Chuột phải (Use / Place)

    public InputRecord(boolean forward, boolean back, boolean left, boolean right, 
                       boolean jumping, boolean sneaking, boolean attacking, boolean usingItem) {
        this.pressingForward = forward;
        this.pressingBack = back;
        this.pressingLeft = left;
        this.pressingRight = right;
        this.jumping = jumping;
        this.sneaking = sneaking;
        this.attacking = attacking;
        this.usingItem = usingItem;
    }
}
