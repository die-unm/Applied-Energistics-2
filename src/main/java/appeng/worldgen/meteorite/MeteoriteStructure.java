package appeng.worldgen.meteorite;

import com.mojang.serialization.Codec;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.gen.ChunkRandom;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.feature.DefaultFeatureConfig;
import net.minecraft.world.gen.feature.StructureFeature;

import appeng.core.AppEng;

public class MeteoriteStructure extends StructureFeature<DefaultFeatureConfig> {

    public static final Identifier ID = AppEng.makeId("meteorite");

    public static final StructureFeature<DefaultFeatureConfig> INSTANCE = new MeteoriteStructure(
            DefaultFeatureConfig.CODEC);

    public MeteoriteStructure(Codec<DefaultFeatureConfig> configCodec) {
        super(configCodec);
    }

    @Override
    public boolean shouldStartAt(ChunkGenerator generator, BiomeSource biomeSource, long seed, ChunkRandom randIn,
            int chunkX, int chunkZ, Biome biome, ChunkPos chunkPos2, DefaultFeatureConfig featureConfig) {
        return randIn.nextBoolean();
    }

    @Override
    public StructureStartFactory<DefaultFeatureConfig> getStructureStartFactory() {
        return MeteoriteStructureStart::new;
    }

}
