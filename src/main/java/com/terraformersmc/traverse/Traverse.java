package com.terraformersmc.traverse;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.terraformersmc.terraform.config.BiomeConfigHandler;
import com.terraformersmc.traverse.biome.TraverseBiomes;
import com.terraformersmc.traverse.block.TraverseBlocks;
import com.terraformersmc.traverse.entity.TraverseEntities;
import com.terraformersmc.traverse.generation.TraverseGeneration;
import com.terraformersmc.traverse.generator.GenDataCommand;
import com.terraformersmc.traverse.surfacebuilder.TraverseSurfaceBuilders;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.itemgroup.FabricItemGroupBuilder;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.dynamic.RegistryElementCodec;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.biome.GenerationSettings;
import net.minecraft.world.gen.surfacebuilder.ConfiguredSurfaceBuilder;

public class Traverse implements ModInitializer {

	public static final String MOD_ID = "traverse";
	public static final BiomeConfigHandler BIOME_CONFIG_HANDLER = new BiomeConfigHandler(MOD_ID);

	private static void register() {
		TraverseBlocks.register();
		TraverseEntities.register();
		TraverseSurfaceBuilders.register();
		TraverseBiomes.register();
		TraverseGeneration.register();

		FabricItemGroupBuilder.create(new Identifier(MOD_ID, "items")).icon(() -> TraverseBlocks.FIR_SAPLING.asItem().getDefaultStack()).appendItems(stacks -> Registry.ITEM.forEach(item -> {
			if (Registry.ITEM.getId(item).getNamespace().equals(MOD_ID)) {
				item.appendStacks(item.getGroup(), (DefaultedList<ItemStack>) stacks);
			}
		})).build();
	}

	@Override
	public void onInitialize() {
		register();
		registerCommand();
	}

	public void registerCommand() {
		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
			GenDataCommand.dataGenCommand(dispatcher);
		});
	}
}
