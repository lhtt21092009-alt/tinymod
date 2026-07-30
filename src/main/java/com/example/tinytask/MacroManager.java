package com.example.tinytask;

import com.example.tinytask.mixin.MinecraftClientAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class MacroManager {
    public enum State { IDLE, RECORDING, PLAYING }

    // Chỉ cho phép chữ/số/gạch dưới/gạch ngang trong tên file macro (chặn tên rỗng, ký tự lạ, path traversal)
    private static final Pattern SAFE_FILENAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private State state = State.IDLE;
    private final List<InputRecord> recordedTicks = new ArrayList<>();
    private int playbackIndex = 0;
    private boolean loop = false;
    
    private String currentFileName = "default";
    private final Path macroDir;

    public MacroManager() {
        // Dùng thư mục config chuẩn của Fabric (an toàn hơn Paths.get("config",...) 
        // vì không phụ thuộc vào working directory lúc chạy game)
        macroDir = FabricLoader.getInstance().getConfigDir().resolve("mctinytask");
        try {
            Files.createDirectories(macroDir);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public List<String> getSavedMacroFiles() {
        File folder = macroDir.toFile();
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".txt"));
        if (files == null) return Collections.emptyList();

        List<String> fileNames = new ArrayList<>();
        for (File f : files) {
            String name = f.getName();
            fileNames.add(name.substring(0, name.length() - 4));
        }
        return fileNames;
    }

    public void startRecording(MinecraftClient client, String fileName) {
        if (state == State.PLAYING) {
            sendMessage(client, "§c[TinyTask] Đang phát lại macro, gõ \\stop trước!");
            return;
        }
        if (state == State.RECORDING) {
            sendMessage(client, "§c[TinyTask] Đang ghi dở file \"" + currentFileName + "\", gõ \\stop trước để lưu rồi mới ghi file mới!");
            return;
        }
        if (!SAFE_FILENAME.matcher(fileName).matches()) {
            sendMessage(client, "§c[TinyTask] Tên file không hợp lệ! Chỉ dùng chữ, số, _ , - (tối đa 64 ký tự).");
            return;
        }
        this.currentFileName = fileName;
        recordedTicks.clear();
        state = State.RECORDING;
        sendMessage(client, "§a[TinyTask] Bắt đầu GHI vào file: " + fileName);
    }

    public void startPlaying(MinecraftClient client, String fileName, boolean loopForever) {
        if (state == State.RECORDING) {
            sendMessage(client, "§c[TinyTask] Đang ghi thao tác, gõ \\stop trước!");
            return;
        }
        if (state == State.PLAYING) {
            sendMessage(client, "§c[TinyTask] Đang phát macro khác, gõ \\stop trước rồi hãy \\start lại!");
            return;
        }
        if (!SAFE_FILENAME.matcher(fileName).matches()) {
            sendMessage(client, "§c[TinyTask] Tên file không hợp lệ!");
            return;
        }

        File file = macroDir.resolve(fileName + ".txt").toFile();
        if (!file.exists()) {
            sendMessage(client, "§c[TinyTask] Không tìm thấy file: " + fileName + ".txt");
            return;
        }

        boolean loaded = loadMacroFromFile(file);
        if (!loaded) {
            sendMessage(client, "§c[TinyTask] Lỗi khi đọc file macro: " + fileName + ".txt");
            return;
        }
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
        if (!SAFE_FILENAME.matcher(fileName).matches()) {
            sendMessage(client, "§c[TinyTask] Tên file không hợp lệ!");
            return;
        }

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
            boolean saved = saveMacroToFile();
            state = State.IDLE;
            if (saved) {
                sendMessage(client, "§a[TinyTask] Đã DỪNG ghi và LƯU: " + currentFileName + ".txt (" + recordedTicks.size() + " ticks)");
            } else {
                sendMessage(client, "§c[TinyTask] Đã DỪNG ghi nhưng LƯU FILE THẤT BẠI: " + currentFileName + ".txt");
            }
        } else if (state == State.PLAYING) {
            state = State.IDLE;
            resetPlayerInputs(client);
            sendMessage(client, "§c[TinyTask] Đã DỪNG phát lại.");
        } else {
            sendMessage(client, "§e[TinyTask] Không có tác vụ nào đang chạy.");
        }
    }

    public void onTick(MinecraftClient client) {
        if (client.player == null || client.options == null) return;

        if (state == State.RECORDING) {
            int hotbarCount = Math.min(InputRecord.HOTBAR_SLOTS, client.options.hotbarKeys.length);
            boolean[] hotbar = new boolean[InputRecord.HOTBAR_SLOTS];
            for (int i = 0; i < hotbarCount; i++) {
                hotbar[i] = client.options.hotbarKeys[i].isPressed();
            }

            InputRecord record = new InputRecord(
                client.options.forwardKey.isPressed(),
                client.options.backKey.isPressed(),
                client.options.leftKey.isPressed(),
                client.options.rightKey.isPressed(),
                client.options.jumpKey.isPressed(),
                client.options.sneakKey.isPressed(),
                client.options.attackKey.isPressed(),
                client.options.useKey.isPressed(),
                hotbar
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

            // Set phím di chuyển
            client.options.forwardKey.setPressed(record.pressingForward);
            client.options.backKey.setPressed(record.pressingBack);
            client.options.leftKey.setPressed(record.pressingLeft);
            client.options.rightKey.setPressed(record.pressingRight);
            client.options.jumpKey.setPressed(record.jumping);
            client.options.sneakKey.setPressed(record.sneaking);

            // FIX CHUỘT TRÁI QUA ACCESSOR MIXIN
            client.options.attackKey.setPressed(record.attacking);
            if (record.attacking) {
                KeyBinding.onKeyPressed(client.options.attackKey.getDefaultKey());
            }
            // Luôn báo trạng thái true/false cho từng tick, không chỉ khi true,
            // nếu không thanh tiến trình đào block sẽ bị "kẹt" khi nhả chuột giữa chừng.
            if (client.interactionManager != null) {
                ((MinecraftClientAccessor) client).invokeHandleBlockBreaking(record.attacking && client.crosshairTarget != null);
            }

            // FIX CHUỘT PHẢI
            client.options.useKey.setPressed(record.usingItem);
            if (record.usingItem) {
                KeyBinding.onKeyPressed(client.options.useKey.getDefaultKey());
            }

            // FIX PHÍM SỐ 1..9 (chuyển ô hotbar)
            int hotbarCount = Math.min(InputRecord.HOTBAR_SLOTS, client.options.hotbarKeys.length);
            for (int i = 0; i < hotbarCount; i++) {
                boolean pressed = record.hotbarKeys[i];
                client.options.hotbarKeys[i].setPressed(pressed);
                if (pressed) {
                    KeyBinding.onKeyPressed(client.options.hotbarKeys[i].getDefaultKey());
                }
            }
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
        if (client.interactionManager != null) {
            ((MinecraftClientAccessor) client).invokeHandleBlockBreaking(false);
        }
        int hotbarCount = Math.min(InputRecord.HOTBAR_SLOTS, client.options.hotbarKeys.length);
        for (int i = 0; i < hotbarCount; i++) {
            client.options.hotbarKeys[i].setPressed(false);
        }
    }

    private boolean saveMacroToFile() {
        File file = macroDir.resolve(currentFileName + ".txt").toFile();
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            for (InputRecord r : recordedTicks) {
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("%b,%b,%b,%b,%b,%b,%b,%b",
                    r.pressingForward, r.pressingBack, r.pressingLeft, r.pressingRight,
                    r.jumping, r.sneaking, r.attacking, r.usingItem));
                for (int i = 0; i < InputRecord.HOTBAR_SLOTS; i++) {
                    sb.append(",").append(r.hotbarKeys[i]);
                }
                sb.append("\n");
                writer.write(sb.toString());
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private boolean loadMacroFromFile(File file) {
        List<InputRecord> loaded = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                int expected = 8 + InputRecord.HOTBAR_SLOTS;
                if (parts.length == expected || parts.length == 8) {
                    boolean[] hotbar = new boolean[InputRecord.HOTBAR_SLOTS];
                    if (parts.length == expected) {
                        for (int i = 0; i < InputRecord.HOTBAR_SLOTS; i++) {
                            hotbar[i] = Boolean.parseBoolean(parts[8 + i]);
                        }
                    } // file macro cũ (8 cột) không có dữ liệu hotbar -> mặc định false

                    InputRecord r = new InputRecord(
                        Boolean.parseBoolean(parts[0]), Boolean.parseBoolean(parts[1]),
                        Boolean.parseBoolean(parts[2]), Boolean.parseBoolean(parts[3]),
                        Boolean.parseBoolean(parts[4]), Boolean.parseBoolean(parts[5]),
                        Boolean.parseBoolean(parts[6]), Boolean.parseBoolean(parts[7]),
                        hotbar
                    );
                    loaded.add(r);
                }
                // dòng sai định dạng: bỏ qua, không làm hỏng toàn bộ macro
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        recordedTicks.clear();
        recordedTicks.addAll(loaded);
        return true;
    }

    private void sendMessage(MinecraftClient client, String text) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(text), false);
        }
    }
}
