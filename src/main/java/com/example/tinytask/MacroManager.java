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
import java.util.*;
import java.util.regex.Pattern;

public class MacroManager {
    public enum State { IDLE, RECORDING, PLAYING }

    // Chỉ cho phép chữ/số/gạch dưới/gạch ngang trong tên file macro (chặn tên rỗng, ký tự lạ, path traversal)
    private static final Pattern SAFE_FILENAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private State state = State.IDLE;
    private String currentFileName = "default";
    private final Path macroDir;

    // Dữ liệu macro: mỗi phần tử là 1 TICK, chứa tên (translation key) các phím ĐANG được giữ ở tick đó.
    // VD tick đó có W và phím "1" đang giữ -> {"key.forward", "key.hotbar.1"}.
    // Không có nút nào giữ -> tập rỗng (ghi ra file là 1 dòng trống).
    private final List<Set<String>> tickData = new ArrayList<>();

    // ===== Trạng thái khi đang PHÁT =====
    private int playTick = 0;
    private boolean loop = false;
    private Set<String> prevPlayKeys = new HashSet<>();
    private Map<String, KeyBinding> keyBindingByName = new HashMap<>();

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
        tickData.clear();
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
        if (tickData.isEmpty()) {
            sendMessage(client, "§e[TinyTask] File rỗng!");
            return;
        }

        // Cache map tên phím -> KeyBinding một lần cho cả phiên phát
        keyBindingByName = new HashMap<>();
        for (KeyBinding kb : client.options.allKeys) {
            if (kb != null) keyBindingByName.put(kb.getTranslationKey(), kb);
        }

        this.playTick = 0;
        this.prevPlayKeys = new HashSet<>();
        this.loop = loopForever;
        this.state = State.PLAYING;
        sendMessage(client, "§a[TinyTask] Đang PHÁT file: " + fileName + " (" + tickData.size() + " tick)"
            + (loopForever ? " - Chạy vô hạn" : " - Chạy 1 lần"));
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
                sendMessage(client, "§a[TinyTask] Đã DỪNG ghi và LƯU: " + currentFileName + ".txt (" + tickData.size() + " tick)");
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
            // Chụp lại TÊN của mọi phím đang giữ ở tick này - phím vừa nhấn sẽ xuất hiện NGAY LẬP TỨC
            // trong tick đó, không cần chờ hay ghi thêm sự kiện thả riêng.
            Set<String> current = new HashSet<>();
            for (KeyBinding kb : client.options.allKeys) {
                if (kb != null && kb.isPressed()) current.add(kb.getTranslationKey());
            }
            tickData.add(current);
        }
        else if (state == State.PLAYING) {
            if (playTick >= tickData.size()) {
                if (loop) {
                    playTick = 0;
                    prevPlayKeys = new HashSet<>();
                } else {
                    state = State.IDLE;
                    resetPlayerInputs(client);
                    sendMessage(client, "§e[TinyTask] Hoàn tất phát lại macro.");
                    return;
                }
            }

            Set<String> current = tickData.get(playTick);

            for (KeyBinding kb : client.options.allKeys) {
                if (kb == null) continue;
                String id = kb.getTranslationKey();
                boolean shouldPress = current.contains(id);
                boolean wasPressed = prevPlayKeys.contains(id);
                kb.setPressed(shouldPress);
                if (shouldPress && !wasPressed) {
                    // Chỉ bắn sự kiện "vừa bấm" đúng lúc chuyển từ nhả sang nhấn, không lặp lại mỗi tick khi giữ.
                    // Dùng phím ĐANG THỰC SỰ được gán (không phải phím mặc định), nên macro vẫn đúng dù
                    // người chơi đã đổi keybind trong Options.
                    InputUtil.Key boundKey = InputUtil.fromTranslationKey(kb.getBoundKeyTranslationKey());
                    KeyBinding.onKeyPressed(boundKey);
                }
            }

            // Tiến trình đào block (mining) cần cập nhật LIÊN TỤC mỗi tick theo trạng thái giữ thật,
            // để thanh đào chạy mượt, không bị kẹt khi nhả chuột giữa chừng.
            boolean attackingNow = current.contains(client.options.attackKey.getTranslationKey());
            if (client.interactionManager != null) {
                ((MinecraftClientAccessor) client).invokeHandleBlockBreaking(attackingNow && client.crosshairTarget != null);
            }

            prevPlayKeys = current;
            playTick++;
        }
    }

    private void resetPlayerInputs(MinecraftClient client) {
        if (client.options == null) return;
        for (KeyBinding kb : client.options.allKeys) {
            if (kb != null) kb.setPressed(false);
        }
        prevPlayKeys = new HashSet<>();
        if (client.interactionManager != null) {
            ((MinecraftClientAccessor) client).invokeHandleBlockBreaking(false);
        }
    }

    private boolean saveMacroToFile() {
        File file = macroDir.resolve(currentFileName + ".txt").toFile();
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            for (Set<String> tick : tickData) {
                writer.write(String.join(",", tick));
                writer.write("\n");
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private boolean loadMacroFromFile(File file) {
        List<Set<String>> loaded = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Set<String> keys = new HashSet<>();
                if (!line.isEmpty()) {
                    for (String k : line.split(",")) {
                        if (!k.isEmpty()) keys.add(k);
                    }
                }
                loaded.add(keys); // dòng trống -> tick không giữ phím nào, vẫn tính là 1 tick hợp lệ
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        tickData.clear();
        tickData.addAll(loaded);
        return true;
    }

    private void sendMessage(MinecraftClient client, String text) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(text), false);
        }
    }
}
