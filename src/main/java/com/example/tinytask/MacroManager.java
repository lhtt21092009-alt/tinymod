package com.example.tinytask;

import com.example.tinytask.mixin.MinecraftClientAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class MacroManager {
    public enum State { IDLE, RECORDING, PLAYING }

    // Chỉ cho phép chữ/số/gạch dưới/gạch ngang trong tên file macro (chặn tên rỗng, ký tự lạ, path traversal)
    private static final Pattern SAFE_FILENAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private State state = State.IDLE;
    private final List<InputRecord> recordedTicks = new ArrayList<>();
    private int playbackIndex = 0;
    private boolean loop = false;

    // Tập hợp các phím (translation key) đang được coi là "đang giữ" ở tick TRƯỚC ĐÓ lúc phát lại.
    // Dùng để chỉ bắn sự kiện "vừa bấm xuống" (rising edge) đúng 1 lần, không lặp lại mỗi tick khi giữ.
    private Set<String> prevPressedKeys = new HashSet<>();

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
        this.prevPressedKeys = new HashSet<>();
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
            List<String> pressed = new ArrayList<>();
            for (KeyBinding kb : client.options.allKeys) {
                if (kb == null || isMovementKey(client, kb)) continue; // di chuyển đã ghi riêng bên dưới
                if (kb.isPressed()) {
                    pressed.add(kb.getTranslationKey());
                }
            }

            InputRecord record = new InputRecord(
                client.options.forwardKey.isPressed(),
                client.options.backKey.isPressed(),
                client.options.leftKey.isPressed(),
                client.options.rightKey.isPressed(),
                client.options.jumpKey.isPressed(),
                client.options.sneakKey.isPressed(),
                pressed
            );
            recordedTicks.add(record);
        }
        else if (state == State.PLAYING) {
            if (playbackIndex >= recordedTicks.size()) {
                if (loop) {
                    playbackIndex = 0;
                    prevPressedKeys = new HashSet<>();
                } else {
                    state = State.IDLE;
                    resetPlayerInputs(client);
                    sendMessage(client, "§e[TinyTask] Hoàn tất phát lại macro.");
                    return;
                }
            }

            InputRecord record = recordedTicks.get(playbackIndex++);

            // Set phím di chuyển (giữ liên tục theo trạng thái ghi được, không cần edge-trigger)
            client.options.forwardKey.setPressed(record.pressingForward);
            client.options.backKey.setPressed(record.pressingBack);
            client.options.leftKey.setPressed(record.pressingLeft);
            client.options.rightKey.setPressed(record.pressingRight);
            client.options.jumpKey.setPressed(record.jumping);
            client.options.sneakKey.setPressed(record.sneaking);

            Set<String> currentKeys = new HashSet<>(record.pressedKeys);

            for (KeyBinding kb : client.options.allKeys) {
                if (kb == null || isMovementKey(client, kb)) continue;
                String id = kb.getTranslationKey();
                boolean shouldPress = currentKeys.contains(id);
                boolean wasPressed = prevPressedKeys.contains(id);
                kb.setPressed(shouldPress);
                if (shouldPress && !wasPressed) {
                    // Chỉ bắn sự kiện "vừa bấm" đúng 1 lần tại cạnh lên, không lặp lại mỗi tick khi giữ.
                    // Dùng phím ĐANG THỰC SỰ được gán (không phải phím mặc định) để macro vẫn đúng
                    // dù người chơi đã đổi keybind trong Options.
                    InputUtil.Key boundKey = InputUtil.fromTranslationKey(kb.getBoundKeyTranslationKey());
                    KeyBinding.onKeyPressed(boundKey);
                }
            }

            // Riêng tiến trình đào block (mining) cần cập nhật LIÊN TỤC theo trạng thái giữ/nhả
            // thật sự mỗi tick (khác với sự kiện "vừa bấm" ở trên) để thanh đào chạy mượt, không bị kẹt.
            boolean attackingNow = currentKeys.contains(client.options.attackKey.getTranslationKey());
            if (client.interactionManager != null) {
                ((MinecraftClientAccessor) client).invokeHandleBlockBreaking(attackingNow && client.crosshairTarget != null);
            }

            prevPressedKeys = currentKeys;
        }
    }

    // Các phím di chuyển được ghi/phát riêng (trạng thái giữ liên tục), nên bỏ qua khi quét allKeys
    // để tránh xử lý trùng lặp / gọi onKeyPressed sai chỗ cho nhóm phím này.
    private boolean isMovementKey(MinecraftClient client, KeyBinding kb) {
        return kb == client.options.forwardKey || kb == client.options.backKey
            || kb == client.options.leftKey || kb == client.options.rightKey
            || kb == client.options.jumpKey || kb == client.options.sneakKey;
    }

    private void resetPlayerInputs(MinecraftClient client) {
        if (client.options == null) return;
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sneakKey.setPressed(false);

        // Nhả TẤT CẢ các phím khác có thể đang bị macro giữ (attack, use, hotbar, drop, swapHands...)
        for (KeyBinding kb : client.options.allKeys) {
            if (kb == null || isMovementKey(client, kb)) continue;
            kb.setPressed(false);
        }

        if (client.interactionManager != null) {
            ((MinecraftClientAccessor) client).invokeHandleBlockBreaking(false);
        }
        prevPressedKeys = new HashSet<>();
    }

    private boolean saveMacroToFile() {
        File file = macroDir.resolve(currentFileName + ".txt").toFile();
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            for (InputRecord r : recordedTicks) {
                StringBuilder sb = new StringBuilder();
                // Phần cố định: 6 cột di chuyển, ngăn cách với danh sách phím bằng dấu ';'
                sb.append(String.format("%b,%b,%b,%b,%b,%b;",
                    r.pressingForward, r.pressingBack, r.pressingLeft, r.pressingRight,
                    r.jumping, r.sneaking));
                // Phần động: tên các phím đang được giữ, ngăn cách bằng dấu ','
                sb.append(String.join(",", r.pressedKeys));
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
                int sep = line.indexOf(';');
                if (sep < 0) continue; // dòng sai định dạng (macro ghi bằng bản cũ true/false) -> bỏ qua

                String movementPart = line.substring(0, sep);
                String keysPart = line.substring(sep + 1);

                String[] m = movementPart.split(",", -1);
                if (m.length != 6) continue; // dòng sai định dạng -> bỏ qua, không làm hỏng toàn bộ macro

                List<String> keys = new ArrayList<>();
                if (!keysPart.isEmpty()) {
                    for (String k : keysPart.split(",")) {
                        if (!k.isEmpty()) keys.add(k);
                    }
                }

                InputRecord r = new InputRecord(
                    Boolean.parseBoolean(m[0]), Boolean.parseBoolean(m[1]),
                    Boolean.parseBoolean(m[2]), Boolean.parseBoolean(m[3]),
                    Boolean.parseBoolean(m[4]), Boolean.parseBoolean(m[5]),
                    keys
                );
                loaded.add(r);
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
