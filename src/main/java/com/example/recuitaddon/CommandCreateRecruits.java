// СТАТУС: ИЗМЕНЕНО (Добавлено автодополнение по Tab и автоматическое распределение по группам)
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CommandCreateRecruits {

    // Сопоставление типов рекрутов с именами групп в моде Recruits
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
                                        // Вариант без типа и игрока
                                        .executes(ctx -> createRecruits(ctx, ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "count"), "recruit"))
                                        // Вариант с типом, но без игрока
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                        ForgeRegistries.ENTITY_TYPES.getValues().stream()
                                                                .filter(type -> {
                                                                    ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(type);
                                                                    return key != null && key.getNamespace().equals("recruits");
                                                                })
                                                                .filter(type -> AbstractRecruitEntity.class.isAssignableFrom(type.getBaseClass()))
                                                                .map(type -> ForgeRegistries.ENTITY_TYPES.getKey(type).getPath())
                                                                .collect(Collectors.toList()),
                                                        builder
                                                ))
                                                .executes(ctx -> createRecruits(ctx, ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "count"), StringArgumentType.getString(ctx, "type")))
                                                // Вариант с типом и игроком
                                                .then(Commands.argument("targetPlayer", EntityArgument.player())
                                                        .executes(ctx -> createRecruits(ctx, EntityArgument.getPlayer(ctx, "targetPlayer"), IntegerArgumentType.getInteger(ctx, "count"), StringArgumentType.getString(ctx, "type")))
                                                )
                                        )
                                )
                        )
        );
    }

    private static int createRecruits(CommandContext<CommandSourceStack> ctx, ServerPlayer target, int count, String typeName) {
        ServerLevel level = (ServerLevel) target.level();
        RecruitsGroupsManager groupsManager = RecruitEvents.recruitsGroupsManager;

        // Получаем тип сущности по имени
        ResourceLocation rl = new ResourceLocation("recruits", typeName.equals("shieldman") ? "recruit_shieldman" : typeName);
        EntityType<?> entityType = ForgeRegistries.ENTITY_TYPES.getValue(rl);
        if (entityType == null || !(entityType.create(level) instanceof AbstractRecruitEntity)) {
            ctx.getSource().sendFailure(Component.literal("§cНеизвестный тип рекрута: " + typeName + ". Использую обычного рекрута."));
            rl = new ResourceLocation("recruits", "recruit");
            entityType = ForgeRegistries.ENTITY_TYPES.getValue(rl);
        }

        // Получаем или создаём базовые группы игрока
        List<RecruitsGroup> groups = groupsManager.getPlayerGroups(target);

        // Определяем имя группы для данного типа рекрута
        String groupName = TYPE_TO_GROUP.getOrDefault(typeName, "Infantry");
        RecruitsGroup group = groups.stream()
                .filter(g -> g.getName().equalsIgnoreCase(groupName))
                .findFirst()
                .orElse(groups.get(0)); // Если группы нет, используем первую доступную

        for (int i = 0; i < count; i++) {
            if (entityType == null) break;

            AbstractRecruitEntity recruit = (AbstractRecruitEntity) entityType.create(level);
            if (recruit == null) continue;

            recruit.moveTo(target.getX(), target.getY(), target.getZ(), target.getYRot(), 0.0F);
            recruit.finalizeSpawn(level, level.getCurrentDifficultyAt(target.blockPosition()), MobSpawnType.COMMAND, null, null);

            level.addFreshEntity(recruit);

            // Полный найм: устанавливает владельца, группу и обновляет менеджеры
            recruit.hire(target, group, false);
        }

        final String finalTypeName = typeName;
        final String finalGroupName = groupName;
        ctx.getSource().sendSuccess(() -> Component.literal("§aСоздано " + count + " рекрутов типа '" + finalTypeName + "' в отряде '" + finalGroupName + "' для " + target.getName().getString()), false);
        return count;
    }
}