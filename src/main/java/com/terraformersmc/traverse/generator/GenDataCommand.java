package com.terraformersmc.traverse.generator;

import com.google.gson.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.registry.BuiltinRegistries;
import net.minecraft.util.registry.DynamicRegistryManager;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.carver.ConfiguredCarver;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import net.minecraft.world.gen.feature.ConfiguredStructureFeature;
import net.minecraft.world.gen.surfacebuilder.ConfiguredSurfaceBuilder;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

@SuppressWarnings("deprecation")
public class GenDataCommand {

	public static void dataGenCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
		String commandString = "gendata";
		List<String> modIdList = new ArrayList<>();
		FabricLoader.getInstance().getAllMods().forEach(modContainer -> {
			String modId = modContainer.getMetadata().getId();
			if (!modId.contains("fabric"))
				modIdList.add(modId);
		});

		LiteralCommandNode<ServerCommandSource> source = dispatcher.register(CommandManager.literal(commandString).then(CommandManager.argument("modid", StringArgumentType.string()).suggests((ctx, sb) -> CommandSource.suggestMatching(modIdList.stream(), sb)).executes(cs -> {
			GenDataCommand.createBiomeDatapack(cs.getArgument("modid", String.class), cs);
			return 1;
		})));
		dispatcher.register(CommandManager.literal(commandString).redirect(source));
	}

	public static void createBiomeDatapack(String modId, CommandContext<ServerCommandSource> commandSource) {
		List<Biome> biomeList = new ArrayList<>();
		boolean stopSpamFlag = false;
		Path dataPackPath = dataPackPath(commandSource.getSource().getWorld().getServer().getSavePath(WorldSavePath.DATAPACKS), modId);
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		DynamicRegistryManager manager = commandSource.getSource().getMinecraftServer().getRegistryManager();
		Registry<Biome> biomeRegistry = manager.get(Registry.BIOME_KEY);
		Registry<ConfiguredFeature<?, ?>> featuresRegistry = manager.get(Registry.CONFIGURED_FEATURE_WORLDGEN);
		Registry<ConfiguredStructureFeature<?, ?>> structuresRegistry = manager.get(Registry.CONFIGURED_STRUCTURE_FEATURE_WORLDGEN);
		Registry<ConfiguredCarver<?>> carverRegistry = manager.get(Registry.CONFIGURED_CARVER_WORLDGEN);

		for (Map.Entry<RegistryKey<Biome>, Biome> biome : biomeRegistry.getEntries()) {
			if (Objects.requireNonNull(biomeRegistry.getKey(biome.getValue())).toString().contains(modId)) {
				biomeList.add(biome.getValue());
			}
		}

		if (biomeList.size() > 0) {
			for (Biome biome : biomeList) {
				Identifier key = biomeRegistry.getId(biome);
				if (key != null) {
					Path biomeJsonPath = jsonPath(dataPackPath, key, modId);
					Function<Supplier<Biome>, DataResult<JsonElement>> biomeCodec = JsonOps.INSTANCE.withEncoder(Biome.REGISTRY_CODEC);
					try {
						if (!Files.exists(biomeJsonPath)) {
							Files.createDirectories(biomeJsonPath.getParent());
							Optional<JsonElement> optional = (biomeCodec.apply(() -> biome).result());
							if (optional.isPresent()) {
								JsonElement root = optional.get();
								JsonArray features = new JsonArray();
								for (List<Supplier<ConfiguredFeature<?, ?>>> list : biome.getGenerationSettings().getFeatures()) {
									JsonArray stage = new JsonArray();
									for (Supplier<ConfiguredFeature<?, ?>> feature : list) {
										featuresRegistry.getKey(feature.get()).ifPresent(k -> stage.add(k.getValue().toString()));
									}
									features.add(stage);
								}
								root.getAsJsonObject().add("features", features);
								String surfaceBuilder = root.getAsJsonObject().get("surface_builder").getAsJsonObject().get("type").getAsString();
								root.getAsJsonObject().addProperty("surface_builder", surfaceBuilder);

								JsonObject carvers = new JsonObject();
								for (GenerationStep.Carver step : GenerationStep.Carver.values()) {
									JsonArray stage = new JsonArray();
									for (Supplier<ConfiguredCarver<?>> carver : biome.getGenerationSettings().getCarversForStep(step)) {
										carverRegistry.getKey(carver.get()).ifPresent(k -> stage.add(k.getValue().toString()));
									}
									if (stage.size() > 0) {
										carvers.add(step.asString(), stage);
									}
								}
								root.getAsJsonObject().add("carvers", carvers);
								JsonArray starts = new JsonArray();
								for (Supplier<ConfiguredStructureFeature<?, ?>> start : biome.getGenerationSettings().getStructureFeatures()) {
									structuresRegistry.getKey(start.get()).ifPresent(k -> starts.add(k.getValue().toString()));
								}
								root.getAsJsonObject().add("starts", starts);
								Files.write(biomeJsonPath, gson.toJson(root).getBytes());
							}
						}
					} catch (IOException e) {
						if (!stopSpamFlag) {
							commandSource.getSource().sendFeedback(new TranslatableText("commands.gendata.failed", modId).styled(text -> text.withColor(TextColor.fromFormatting(Formatting.RED))), false);
							stopSpamFlag = true;
						}
					}
				}
			}

			try {
				createPackMCMeta(dataPackPath, modId);
			} catch (IOException e) {
				commandSource.getSource().sendFeedback(new TranslatableText("commands.gendata.mcmeta.failed", modId).styled(text -> text.withColor(TextColor.fromFormatting(Formatting.RED))), false);
			}

			Text filePathText = (new LiteralText(dataPackPath.toString())).formatted(Formatting.UNDERLINE).styled(text -> text.withColor(TextColor.fromFormatting(Formatting.GREEN)).withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, dataPackPath.toString())).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TranslatableText("commands.gendata.hovertext"))));

			commandSource.getSource().sendFeedback(new TranslatableText("commands.gendata.success", commandSource.getArgument("modid", String.class), filePathText), false);
		} else {
			commandSource.getSource().sendFeedback(new TranslatableText("commands.gendata.listisempty", modId).styled(text -> text.withColor(TextColor.fromFormatting(Formatting.RED))), false);
		}
	}

	private static Path jsonPath(Path path, Identifier identifier, String modId) {
		return path.resolve("data/" + modId + "/worldgen/biome/" + identifier.getPath() + ".json");
	}

	private static Path dataPackPath(Path path, String modId) {
		return path.resolve("gendata/" + modId + "-custom");
	}

	//Generate the pack.mcmeta file required for datapacks.
	private static void createPackMCMeta(Path dataPackPath, String modID) throws IOException {
		String fileString = "{\n" +
			"\t\"pack\":{\n" +
			"\t\t\"pack_format\": 6,\n" +
			"\t\t\"description\": \"Custom biome datapack for " + modID + ".\"\n" +
			"\t}\n" +
			"}\n";

		Files.write(dataPackPath.resolve("pack.mcmeta"), fileString.getBytes());
	}
}
