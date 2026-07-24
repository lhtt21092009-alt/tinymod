package com.example.tinytask;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import com.mojang.brigadier.arguments.StringArgumentType;

public class TinyTaskMod implements ClientModInitializer {
    public static final MacroManager macroManager = new MacroManager();

    @Override
    public void onInitializeClient() {
        // Đăng ký lệnh Client Command
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            
            // \record <filename>
            dispatcher.register(ClientCommandManager.literal("record")
                .then(ClientCommandManager.argument("filename", StringArgumentType.word())
                    .executes(context -> {
                        String filename = StringArgumentType.getString(context, "filename");
                        macroManager.startRecording(context.getSource().getClient(), filename);
                        return 1;
                    })
                )
            );

            // \start <filename> [inf]
            dispatcher.register(ClientCommandManager.literal("start")
                .then(ClientCommandManager.argument("filename", StringArgumentType.word())
                    .executes(context -> {
                        String filename = StringArgumentType.getString(context, "filename");
                        macroManager.startPlaying(context.getSource().getClient(), filename, false);
                        return 1;
                    })
                    .then(ClientCommandManager.literal("inf")
                        .executes(context -> {
                            String filename = StringArgumentType.getString(context, "filename");
                            macroManager.startPlaying(context.getSource().getClient(), filename, true);
                            return 1;
                        })
                    )
                )
            );

            // \stop
            dispatcher.register(ClientCommandManager.literal("stop")
                .executes(context -> {
                    macroManager.stopAll(context.getSource().getClient());
                    return 1;
                })
            );
        });

        // Tick loop
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            macroManager.onTick(client);
        });
    }
}
