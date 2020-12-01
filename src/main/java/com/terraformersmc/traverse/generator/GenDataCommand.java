package com.terraformersmc.traverse.generator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.registry.MutableRegistry;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeEffects;
import net.minecraft.world.biome.GenerationSettings;
import net.minecraft.world.biome.SpawnSettings;
import net.minecraft.world.gen.carver.Carver;
import net.minecraft.world.gen.carver.ConfiguredCarver;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import net.minecraft.world.gen.feature.ConfiguredStructureFeature;
import net.minecraft.world.gen.feature.ConfiguredStructureFeatures;
import net.minecraft.world.gen.surfacebuilder.ConfiguredSurfaceBuilder;
import net.minecraft.world.gen.surfacebuilder.SurfaceBuilder;

import java.io.IOException;
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

  public static final Codec<Biome> CODEC = RecordCodecBuilder.create((instance) -> {
    return instance.group(Biome.Weather.CODEC.forGetter((biome) -> {
      return biome.weather;
    }), Biome.Category.CODEC.fieldOf("category").forGetter((biome) -> {
      return biome.category;
    }), Codec.FLOAT.fieldOf("depth").forGetter((biome) -> {
      return biome.depth;
    }), Codec.FLOAT.fieldOf("scale").forGetter((biome) -> {
      return biome.scale;
    }), BiomeEffects.CODEC.fieldOf("effects").forGetter((biome) -> {
      return biome.effects;
    }), GenerationSettings.CODEC.forGetter((biome) -> {
      return biome.generationSettings;
    }), SpawnSettings.CODEC.forGetter((biome) -> {
      return biome.spawnSettings;
    })).apply(instance, Biome::new);
  });

	public static void createBiomeDatapack(String modId, CommandContext<ServerCommandSource> commandSource) {
		List<Biome> biomeList = new ArrayList<>();
		boolean stopSpamFlag = false;
		Path dataPackPath = dataPackPath(commandSource.getSource().getWorld().getServer().getSavePath(WorldSavePath.DATAPACKS), modId);
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		Registry<Biome> biomeRegistry = commandSource.getSource().getMinecraftServer().getRegistryManager().get(Registry.BIOME_KEY);



		//Collect biomes from the datapack registry where biome data is most likely to have been modified by other content adding mods.
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
								Files.write(biomeJsonPath, gson.toJson(optional.get()).getBytes());
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

	private static Path object(Path path, Identifier identifier, String modId, String type) {
		return path.resolve("data/" + modId + "/worldgen/dump/" + type + "/" + identifier.getNamespace() + "." + identifier.getPath() + ".json");
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
