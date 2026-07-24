package com.example.tinytask;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class MacroManager {
    public enum State { IDLE, RECORDING, PLAYING }

    private State state = State.IDLE;
    private final List<InputRecord> recordedTicks = new ArrayList<>();
    private int playbackIndex = 0;
    private boolean loop = false;
    
    private String currentFileName = "default";
    private final Path macroDir;

    public MacroManager() {
        macroDir = Paths.get("config", "mctinytask");
        try {
            Files.createDirectories(macroDir);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void startRecording(MinecraftClient client, String fileName) {
        if (state == State.PLAYING) {
            sendMessage(client, "§c[TinyTask] Đang phát lại macro, gõ \\stop trước!");
            return;
        }
        this.currentFileName = fileName;
        recordedTicks.clear();
        state = State.RECORDING;
        sendMessage(client, "§a[TinyTask] Bắt đầu GHI (chỉ phím) vào file: " + fileName);
    }

    public void startPlaying(MinecraftClient client, String fileName, boolean loopForever) {
        if (state == State.RECORDING) {
            sendMessage(client, "§c[TinyTask] Đang ghi thao tác, gõ \\stop trước!");
            return;
        }

        File file = macroDir.resolve(fileName + ".txt").toFile();
        if (!file.exists()) {
            sendMessage(client, "§c[TinyTask] Không tìm thấy file: " + fileName + ".txt");
            return;
        }

        loadMacroFromFile(file);
        if (recordedTicks.isEmpty()) {
            sendMessage(client, "§e[TinyTask] File rỗng hoặc không đúng định dạng!");
            return;
        }

        this.loop = loopForever;
        this.playbackIndex = 0;
        this.state = State.PLAYING;
        sendMessage(client, "§a[TinyTask] Đang PHÁT file: " + fileName + (loopForever ? " (Chạy vô hạn)" : " (Chạy 1 lần)"));
    }

    public void deleteMacroFile(MinecraftClient client, String fileName) {
        File file = macroDir.resolve(fileName + ".txt").toFile();
        if (!file.exists()) {
            sendMessage(client, "§c[TinyTask] Không tìm thấy file để xóa: " + fileName + ".txt");
            return;
        }

        if (file.delete()) {
            sendMessage(client, "§a[TinyTask] Đã xóa thành công file: " + fileName + ".txt");
        } else {
            sendMessage(client, "§c[TinyTask] Không thể xóa file: " + fileName + ".txt");
        }
    }

    public void stopAll(MinecraftClient client) {
        if (state == State.RECORDING) {
            saveMacroToFile();
            state = State.IDLE;
            sendMessage(client, "§a[TinyTask] Đã DỪNG ghi và LƯU: " + currentFileName + ".txt (" + recordedTicks.size() + " ticks)");
        } else if (state == State.PLAYING) {
            state = State.IDLE;
            resetPlayerInputs(client);
            sendMessage(client, "§c[TinyTask] Đã DỪNG phát lại.");
        } else {
            sendMessage(client, "§e[TinyTask] Không có tác vụ nào đang chạy.");
        }
    }

    public void onTick(MinecraftClient client) {
        if (client.player == null) return;

        if (state == State.RECORDING) {
            // CHỈ GHI TRẠNG THÁI PHÍM - BỎ GÓC NHÌN CHUỘT (YAW / PITCH)
            InputRecord record = new InputRecord(
                client.options.forwardKey.isPressed(),
                client.options.backKey.isPressed(),
                client.options.leftKey.isPressed(),
                client.options.rightKey.isPressed(),
                client.options.jumpKey.isPressed(),
                client.options.sneakKey.isPressed(),
                client.options.attackKey.isPressed(),
                client.options.useKey.isPressed()
            );
            recordedTicks.add(record);
        } 
        else if (state == State.PLAYING) {
            if (playbackIndex >= recordedTicks.size()) {
                if (loop) {
                    playbackIndex = 0;
                } else {
                    state = State.IDLE;
                    resetPlayerInputs(client);
                    sendMessage(client, "§e[TinyTask] Hoàn tất phát lại macro.");
                    return;
                }
            }

            InputRecord record = recordedTicks.get(playbackIndex++);

            // CHỈ PHÁT LẠI PHÍM - MÀN HÌNH TỰ DO XOAY
            client.options.forwardKey.setPressed(record.pressingForward);
            client.options.backKey.setPressed(record.pressingBack);
            client.options.leftKey.setPressed(record.pressingLeft);
            client.options.rightKey.setPressed(record.pressingRight);
            client.options.jumpKey.setPressed(record.jumping);
            client.options.sneakKey.setPressed(record.sneaking);
            client.options.attackKey.setPressed(record.attacking);
            client.options.useKey.setPressed(record.usingItem);
        }
    }

    private void resetPlayerInputs(MinecraftClient client) {
        if (client.options == null) return;
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
        client.options.attackKey.setPressed(false);
        client.options.useKey.setPressed(false);
    }

    private void saveMacroToFile() {
        File file = macroDir.resolve(currentFileName + ".txt").toFile();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            for (InputRecord r : recordedTicks) {
                writer.write(String.format("%b,%b,%b,%b,%b,%b,%b,%b\n",
                    r.pressingForward, r.pressingBack, r.pressingLeft, r.pressingRight, 
                    r.jumping, r.sneaking, r.attacking, r.usingItem));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadMacroFromFile(File file) {
        recordedTicks.clear();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length == 8) {
                    InputRecord r = new InputRecord(
                        Boolean.parseBoolean(parts[0]), Boolean.parseBoolean(parts[1]),
                        Boolean.parseBoolean(parts[2]), Boolean.parseBoolean(parts[3]),
                        Boolean.parseBoolean(parts[4]), Boolean.parseBoolean(parts[5]),
                        Boolean.parseBoolean(parts[6]), Boolean.parseBoolean(parts[7])
                    );
                    recordedTicks.add(r);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendMessage(MinecraftClient client, String text) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(text), false);
        }
    }
}
