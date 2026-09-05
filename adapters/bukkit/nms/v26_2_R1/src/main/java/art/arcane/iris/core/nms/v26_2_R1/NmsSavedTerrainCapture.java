package art.arcane.iris.core.nms.v26_2_R1;

import art.arcane.iris.engine.history.NativeTerrainReceipt;
import art.arcane.iris.engine.history.SavedTerrainChunk;
import art.arcane.iris.util.common.scheduling.J;
import ca.spottedleaf.moonrise.common.PlatformHooks;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

final class NmsSavedTerrainCapture {
    private NmsSavedTerrainCapture() {
    }

    static CompletableFuture<SavedTerrainChunk> capture(World world, int chunkX, int chunkZ, int minimumY, int height) {
        ServerLevel level = ((CraftWorld) world).getHandle();
        CompletableFuture<CompoundTag> snapshot = new CompletableFuture<>();
        J.runRegionFuture(world, chunkX, chunkZ, () -> {
            try {
                NewChunkHolder holder = level.moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(chunkX, chunkZ);
                ChunkAccess chunk = holder == null ? null : holder.getCurrentChunk();
                if (chunk == null) {
                    snapshot.complete(null);
                    return;
                }
                String status = BuiltInRegistries.CHUNK_STATUS.getKey(chunk.getPersistedStatus()).toString();
                if (!SavedTerrainChunk.hasTerrain(status)) {
                    throw new IOException("Native chunk " + chunkX + "," + chunkZ + " has no completed terrain at " + status);
                }
                snapshot.complete(saveSnapshot(level, chunk));
            } catch (Throwable failure) {
                snapshot.completeExceptionally(failure);
            }
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                snapshot.completeExceptionally(failure);
            }
        });
        return snapshot.thenApplyAsync(data -> {
            try {
                if (data == null) {
                    return SavedTerrainChunk.read(world.getWorldFolder().toPath(), chunkX, chunkZ, minimumY, height);
                }
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DataOutputStream output = new DataOutputStream(bytes)) {
                    NbtIo.write(data, output);
                }
                return SavedTerrainChunk.readNbt(bytes.toByteArray(), chunkX, chunkZ, minimumY, height);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    static CompletableFuture<Void> flush(World world) {
        ServerLevel level = ((CraftWorld) world).getHandle();
        List<NewChunkHolder> holders = level.moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolders();
        List<CompletableFuture<Checkpoint>> saves = new ArrayList<>(holders.size());
        for (NewChunkHolder holder : holders) {
            CompletableFuture<Checkpoint> saved = new CompletableFuture<>();
            saves.add(saved);
            J.runRegionFuture(world, holder.chunkX, holder.chunkZ, () -> {
                try {
                    ChunkAccess chunk = holder.getCurrentChunk();
                    if (chunk == null || !chunk.isUnsaved()) {
                        saved.complete(null);
                        return;
                    }
                    CompoundTag data = saveSnapshot(level, chunk);
                    byte[] receipt = data.getCompound("ChunkBukkitValues")
                            .flatMap(values -> values.getByteArray(NativeTerrainReceipt.NBT_KEY)).orElse(null);
                    saved.complete(new Checkpoint(holder.chunkX, holder.chunkZ,
                            BuiltInRegistries.CHUNK_STATUS.getKey(chunk.getPersistedStatus()).toString(), receipt,
                            data.getCompound("ChunkBukkitValues").map(values -> values.getLongOr(
                                    NativeTerrainReceipt.STRUCTURE_ACTIVATION_KEY, 0)).orElse(0L)));
                } catch (Throwable failure) {
                    saved.completeExceptionally(failure);
                }
            }).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    saved.completeExceptionally(failure);
                }
            });
        }
        return CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new)).thenRunAsync(() -> {
            try {
                MoonriseRegionFileIO.flush(level, MoonriseRegionFileIO.RegionFileType.CHUNK_DATA);
                MoonriseRegionFileIO.flushRegionStorages(level, MoonriseRegionFileIO.RegionFileType.CHUNK_DATA);
                for (CompletableFuture<Checkpoint> future : saves) {
                    Checkpoint saved = future.join();
                    if (saved == null) {
                        continue;
                    }
                    SavedTerrainChunk.verifyCheckpoint(world.getWorldFolder().toPath(), saved.chunkX(), saved.chunkZ(),
                            saved.status(), saved.receipt(), saved.structureActivation());
                }
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    private static CompoundTag saveSnapshot(ServerLevel level, ChunkAccess chunk) {
        SerializableChunkData snapshot = SerializableChunkData.copyOf(level, chunk);
        PlatformHooks.get().chunkSyncSave(level, chunk, snapshot);
        CompoundTag data = snapshot.write();
        MoonriseRegionFileIO.scheduleSave(level, chunk.getPos().x(), chunk.getPos().z(), data,
                MoonriseRegionFileIO.RegionFileType.CHUNK_DATA);
        return data;
    }

    private record Checkpoint(int chunkX, int chunkZ, String status, byte[] receipt, long structureActivation) {
    }
}
