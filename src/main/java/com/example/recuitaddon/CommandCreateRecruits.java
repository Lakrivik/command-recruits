package com.example.recuitaddon;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.talhanation.recruits.RecruitEvents;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.world.RecruitsGroup;
import com.talhanation.recruits.world.RecruitsGroupsManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;

public class CommandCreateRecruits {

    private static final Map<String, String> TYPE_TO_REGISTRY = Map.of(
            "recruit", "recruit",
            "shieldman", "recruit_shieldman",
            "bowman", "bowman",
            "crossbowman", "crossbowman",
            "horseman", "horseman",
            "nomad", "nomad"
    );

    private static final Map<String, String> TYPE_TO_GROUP = Map.of(
            "recruit", "Infantry",
            "shieldman", "Infantry",
            "bowman", "Ranged",
            "crossbowman", "Ranged",
            "horseman", "Cavalry",
            "nomad", "Cavalry"
    );

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
                Commands.literal("recruits")
                        .then(Commands.literal("create")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                                        .executes(ctx -> createRecruits(ctx,
                                                ctx.getSource().getPlayerOrException(),
                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                "recruit", null))
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                        TYPE_TO_REGISTRY.keySet(), builder))
                                                .executes(ctx -> createRecruits(ctx,
                                                        ctx.getSource().getPlayerOrException(),
                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                        StringArgumentType.getString(ctx, "type"), null))
                                                .then(Commands.argument("targetPlayer", EntityArgument.player())
                                                        .executes(ctx -> createRecruits(ctx,
                                                                EntityArgument.getPlayer(ctx, "targetPlayer"),
                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                StringArgumentType.getString(ctx, "type"), null))
                                                        .then(Commands.argument("team", StringArgumentType.word())
                                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                                        ctx.getSource().getServer().getScoreboard().getTeamNames(),
                                                                        builder))
                                                                .executes(ctx -> createRecruits(ctx,
                                                                        EntityArgument.getPlayer(ctx, "targetPlayer"),
                                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                                        StringArgumentType.getString(ctx, "type"),
                                                                        StringArgumentType.getString(ctx, "team")))
                                                        )
                                                )
                                        )
                                )
                        )
        );
    }

    private static int createRecruits(CommandContext<CommandSourceStack> ctx, ServerPlayer target,
                                       int count, String typeName, String teamName) {
        ServerLevel level = (ServerLevel) target.level();
        MinecraftServer server = level.getServer();
        Scoreboard scoreboard = server.getScoreboard();

        // Проверка ванильной команды, если указана
        PlayerTeam playerTeam = null;
        if (teamName != null) {
            playerTeam = scoreboard.getPlayerTeam(teamName);
            if (playerTeam == null) {
                ctx.getSource().sendFailure(Component.literal("§cКоманда '" + teamName + "' не найдена. Используйте /team add " + teamName));
                return 0;
            }
            // Проверяем, что целевой игрок действительно в этой команде
            if (!playerTeam.getPlayers().contains(target.getScoreboardName())) {
                ctx.getSource().sendFailure(Component.literal("§c" + target.getName().getString()
                        + " не состоит в команде '" + playerTeam.getName() + "'. Сначала добавьте через /team join " + playerTeam.getName() + " " + target.getName().getString()));
                return 0;
            }
        }

        RecruitsGroupsManager groupsManager = RecruitEvents.recruitsGroupsManager;

        String registryPath = TYPE_TO_REGISTRY.getOrDefault(typeName, typeName);
        ResourceLocation rl = new ResourceLocation("recruits", registryPath);
        EntityType<?> entityType = ForgeRegistries.ENTITY_TYPES.getValue(rl);
        if (entityType == null || !(entityType.create(level) instanceof AbstractRecruitEntity)) {
            ctx.getSource().sendFailure(Component.literal("§cНеизвестный тип рекрута: " + typeName + ". Использую обычного рекрута."));
            rl = new ResourceLocation("recruits", "recruit");
            entityType = ForgeRegistries.ENTITY_TYPES.getValue(rl);
        }

        List<RecruitsGroup> groups = groupsManager.getPlayerGroups(target);
        String groupName = TYPE_TO_GROUP.getOrDefault(typeName, "Infantry");
        RecruitsGroup group = groups.stream()
                .filter(g -> g.getName().equalsIgnoreCase(groupName))
                .findFirst()
                .orElse(groups.get(0));

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            if (entityType == null) break;

            AbstractRecruitEntity recruit = (AbstractRecruitEntity) entityType.create(level);
            if (recruit == null) continue;

            recruit.moveTo(target.getX(), target.getY(), target.getZ(), target.getYRot(), 0.0F);
            recruit.finalizeSpawn(level, level.getCurrentDifficultyAt(target.blockPosition()), MobSpawnType.COMMAND, null, null);
            level.addFreshEntity(recruit);
            recruit.hire(target, group, false);

            // Привязка рекрута к ванильной команде через Scoreboard
            if (playerTeam != null) {
                scoreboard.addPlayerToTeam(recruit.getScoreboardName(), playerTeam);
            }

            spawned++;
        }

        final int finalSpawned = spawned;
        final String finalGroupName = groupName;
        final PlayerTeam finalPlayerTeam = playerTeam;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aСоздано " + finalSpawned + " рекрутов '" + typeName + "' (группа '" + finalGroupName + "') для "
                        + target.getName().getString()
                        + (finalPlayerTeam != null ? " в команде '" + finalPlayerTeam.getName() + "'" : "")
        ), false);
        return spawned;
    }
}