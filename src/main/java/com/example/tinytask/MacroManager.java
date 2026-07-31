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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class MacroManager {
    public enum State { IDLE, RECORDING, PLAYING }

    // Chỉ cho phép chữ/số/gạch dưới/gạch ngang trong tên file macro (chặn tên rỗng, ký tự lạ, path traversal)
    private static final Pattern SAFE_FILENAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private State state = State.IDLE;

    // ====== GHI ======
    // Danh sách tên phím theo ĐÚNG THỨ TỰ đã bấm, chỉ ghi lúc NHẤN XUỐNG, không ghi lúc nhả.
    // Ví dụ: ["key.hotbar.1", "key.hotbar.2", "key.hotbar.3", "key.left.shift"]
    private final List<String> keySequence = new ArrayList<>();
    private Set<String> heldKeysWhileRecording = new HashSet<>(); // Để phát hiện đúng thời điểm "vừa nhấn xuống"

    // ====== PHÁT ======
    private int playbackIndex = 0;          // Đang phát tới phím thứ mấy trong keySequence
    private KeyBinding lastPressedKey = null; // Phím vừa nhấn ở tick trước, để nhả ra trước khi bấm phím kế tiếp
    private boolean loop = false;
    private Map<String, KeyBinding> keyBindingLookup = new HashMap<>(); // Tra cứu nhanh KeyBinding theo tên

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
        keySequence.clear();
        heldKeysWhileRecording = new HashSet<>();
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
        if (keySequence.isEmpty()) {
            sendMessage(client, "§e[TinyTask] File rỗng hoặc không đúng định dạng!");
            return;
        }

        // Tra cứu nhanh KeyBinding theo tên, dựng lại mỗi lần phát để luôn khớp với keybind hiện tại
        keyBindingLookup = new HashMap<>();
        for (KeyBinding kb : client.options.allKeys) {
            if (kb != null) keyBindingLookup.put(kb.getTranslationKey(), kb);
        }

        this.loop = loopForever;
        this.playbackIndex = 0;
        this.lastPressedKey = null;
        this.state = State.PLAYING;
        sendMessage(client, "§a[TinyTask] Đang PHÁT file: " + fileName + " (" + keySequence.size() + " phím)" + (loopForever ? " (Chạy vô hạn)" : " (Chạy 1 lần)"));
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
                sendMessage(client, "§a[TinyTask] Đã DỪNG ghi và LƯU: " + currentFileName + ".txt (" + keySequence.size() + " phím)");
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
            Set<String> heldNow = new HashSet<>();
            for (KeyBinding kb : client.options.allKeys) {
                if (kb != null && kb.isPressed()) {
                    heldNow.add(kb.getTranslationKey());
                }
            }

            // Chỉ ghi lúc phím VỪA NHẤN XUỐNG (không có ở tick trước, có ở tick này).
            // Không quan tâm lúc nhả ra -> file chỉ là 1 chuỗi tên phím theo đúng thứ tự đã bấm.
            for (String key : heldNow) {
                if (!heldKeysWhileRecording.contains(key)) {
                    keySequence.add(key);
                }
            }

            heldKeysWhileRecording = heldNow;
        }
        else if (state == State.PLAYING) {
            // Nhả phím đã bấm ở tick trước trước khi bấm phím kế tiếp,
            // để mỗi phím chỉ là 1 cái "tap" (nhấn - nhả) rời rạc, giống đọc từng track trên đĩa CD.
            if (lastPressedKey != null) {
                boolean wasAttack = (lastPressedKey == client.options.attackKey);
                lastPressedKey.setPressed(false);
                lastPressedKey = null;
                if (wasAttack && client.interactionManager != null) {
                    ((MinecraftClientAccessor) client).invokeHandleBlockBreaking(false);
                }
            }

            if (playbackIndex < keySequence.size()) {
                String keyName = keySequence.get(playbackIndex++);
                KeyBinding kb = keyBindingLookup.get(keyName);
                if (kb != null) {
                    kb.setPressed(true);
                    // Dùng phím ĐANG THỰC SỰ được gán (không phải phím mặc định) để macro vẫn đúng
                    // dù người chơi đã đổi keybind trong Options.
                    InputUtil.Key boundKey = InputUtil.fromTranslationKey(kb.getBoundKeyTranslationKey());
                    KeyBinding.onKeyPressed(boundKey);
                    lastPressedKey = kb;

                    // Nếu phím vừa bấm là chuột trái -> kích hoạt luôn 1 nhịp đào block cho tick này
                    if (kb == client.options.attackKey && client.interactionManager != null) {
                        ((MinecraftClientAccessor) client).invokeHandleBlockBreaking(client.crosshairTarget != null);
                    }
                }
            }

            if (playbackIndex >= keySequence.size()) {
                if (loop) {
                    playbackIndex = 0;
                } else {
                    state = State.IDLE;
                    resetPlayerInputs(client); // nhả phím cuối cùng vừa bấm, tránh bị kẹt phím mãi mãi
                    sendMessage(client, "§e[TinyTask] Hoàn tất phát lại macro.");
                }
            }
        }
    }

    private void resetPlayerInputs(MinecraftClient client) {
        if (client.options == null) return;
        // Nhả TẤT CẢ các phím có thể đang bị macro giữ
        for (KeyBinding kb : client.options.allKeys) {
            if (kb != null) kb.setPressed(false);
        }
        lastPressedKey = null;
        if (client.interactionManager != null) {
            ((MinecraftClientAccessor) client).invokeHandleBlockBreaking(false);
        }
    }

    private boolean saveMacroToFile() {
        File file = macroDir.resolve(currentFileName + ".txt").toFile();
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            // File chỉ là 1 dòng duy nhất: các tên phím cách nhau bằng dấu phẩy, đúng thứ tự đã bấm.
            // Ví dụ: key.hotbar.1,key.hotbar.2,key.hotbar.3,key.left.shift
            writer.write(String.join(",", keySequence));
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private boolean loadMacroFromFile(File file) {
        List<String> loaded = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
            for (String key : content.toString().split(",")) {
                String trimmed = key.trim();
                if (!trimmed.isEmpty()) loaded.add(trimmed);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        keySequence.clear();
        keySequence.addAll(loaded);
        return true;
    }

    private void sendMessage(MinecraftClient client, String text) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(text), false);
        }
    }
}
