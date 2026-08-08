import java.io.File;
import java.io.IOException;
import de.piegames.nbt.ByteTag;
import de.piegames.nbt.CompoundTag;
import de.piegames.nbt.ListTag;
import de.piegames.nbt.LongArrayTag;
import de.piegames.nbt.regionfile.Chunk;
import de.piegames.nbt.regionfile.RegionFile;

public class HighestBlockFinder {
	public static void main(String[] args) throws IOException {
		System.out.println("MC World Highest Block Finder (Debug)");
		String myWorld = "Enter Your World Path Here";

		System.out.println("Scanning world for highest block...");
		Result result = findHighestBlock(myWorld);
		if (result != null) {
			System.out.println("=== FINAL RESULT ===");
			System.out.println("Highest block: " + result.blockId);
			System.out.println("Location: (" + result.x + ", " + result.y + ", " + result.z + ")");
			System.out.println("Successfully loaded region files: " + result.processedFiles);
			System.out.println("Debug info: " + result.debugInfo);
		} else {
			System.out.println("No valid chunks found.");
		}
	}

	private static Result findHighestBlock(String worldPath) throws IOException {
		File regionDir = new File(worldPath, "region");
		if (!regionDir.exists() || !regionDir.isDirectory()) {
			throw new IOException("region folder not found at " + regionDir.getAbsolutePath());
		}

		int highestY = Integer.MIN_VALUE;
		int highestX = 0, highestZ = 0;
		String highestBlockId = "minecraft:air";
		String debugInfo = "";
		int processedRegionCount = 0;
		File[] regionFiles = regionDir.listFiles((dir, name) -> name.endsWith(".mca"));
		if (regionFiles == null) return null;

		System.out.println("Found " + regionFiles.length + " region files.");

		for (File regionFile : regionFiles) {
			if (regionFile.length() < 4096) {
				System.out.println("Skipping empty/small file: " + regionFile.getName());
				continue;
			}

			System.out.println("Processing: " + regionFile.getName());
			try (RegionFile region = new RegionFile(regionFile.toPath())) {
				int chunkCount = 0;
				for (int cx = 0; cx < 32; cx++) {
					for (int cz = 0; cz < 32; cz++) {
						if (!region.hasChunk(cx, cz)) continue;
						chunkCount++;
						Chunk chunk = region.loadChunk(cx, cz);
						if (chunk == null) continue;

						CompoundTag chunkData = chunk.readTag().getAsCompoundTag()
								.orElseThrow(() -> new IllegalStateException("Chunk data invalid"));

						BlockInfo info = getHighestBlockInChunk(chunkData, cx, cz);
						if (info == null) continue;

						if (info.y > highestY) {
							highestY = info.y;
							highestX = info.x;
							highestZ = info.z;
							highestBlockId = info.blockId;
							debugInfo = info.debug;
							System.out.println("New highest: " + highestBlockId + " at (" + highestX + ", " + highestY + ", " + highestZ + ")");
							System.out.println("  Debug: " + debugInfo);
						}
					}
				}
				System.out.println("Processed " + chunkCount + " chunks in " + regionFile.getName());
				if (chunkCount > 0) {
					processedRegionCount++;
				}
			} catch (Exception e) {
				System.err.println("Error processing " + regionFile.getName() + ": " + e.getMessage());
				e.printStackTrace();
			}
		}

		if (highestY == Integer.MIN_VALUE) {
			System.out.println("No valid chunks found.");
			return null;
		}
		return new Result(highestX, highestY, highestZ, highestBlockId, processedRegionCount, debugInfo);
	}

	private static BlockInfo getHighestBlockInChunk(CompoundTag chunkData, int chunkX, int chunkZ) {
		CompoundTag level = chunkData.getAsCompoundTag("Level").orElse(null);
		if (level == null) return null;

		ListTag<CompoundTag> sections = level.getAsListTag("Sections")
				.flatMap(ListTag::getAsCompoundTagList)
				.orElse(null);
		if (sections == null || sections.getValue().isEmpty()) return null;

		int highestY = Integer.MIN_VALUE;
		int highestLocalX = 0, highestLocalZ = 0;
		String highestBlockId = "minecraft:air";
		String debug = "";

		for (CompoundTag section : sections.getValue()) {
			ByteTag yTag = section.getAsByteTag("Y").orElse(null);
			if (yTag == null) continue;
			byte yOffset = yTag.getValue();

			ListTag<CompoundTag> palette = section.getAsListTag("Palette")
					.flatMap(ListTag::getAsCompoundTagList)
					.orElse(null);
			if (palette == null || palette.getValue().isEmpty()) continue;

			LongArrayTag blockStatesTag = section.getAsLongArrayTag("BlockStates").orElse(null);
			long[] blockStates = blockStatesTag == null ? null : blockStatesTag.getValue();

			int paletteSize = palette.getValue().size();
			// 重要：bitsPerEntry 至少为 4
			int bitsPerEntry = (int) Math.ceil(Math.log(paletteSize) / Math.log(2));
			if (bitsPerEntry < 4) bitsPerEntry = 4;

			for (int by = 0; by < 16; by++) {
				for (int bz = 0; bz < 16; bz++) {
					for (int bx = 0; bx < 16; bx++) {
						int blockIndex = (by * 16 + bz) * 16 + bx;
						int paletteIndex;
						if (blockStates == null) {
							paletteIndex = 0;
						} else {
							paletteIndex = getPaletteIndex(blockStates, blockIndex, bitsPerEntry);
						}
						if (paletteIndex >= 0 && paletteIndex < paletteSize) {
							CompoundTag blockState = palette.getValue().get(paletteIndex);
							String name = blockState.getAsStringTag("Name").map(t -> t.getValue()).orElse("minecraft:air");
							if (!"minecraft:air".equals(name)) {
								int actualY = yOffset * 16 + by;
								if (actualY > highestY) {
									highestY = actualY;
									highestLocalX = bx;
									highestLocalZ = bz;
									highestBlockId = name;
									debug = String.format("section Y=%d, local=(%d,%d,%d), paletteIndex=%d, bits=%d, palette size=%d",
											yOffset, bx, by, bz, paletteIndex, bitsPerEntry, paletteSize);
								}
							}
						}
					}
				}
			}
		}

		if (highestY == Integer.MIN_VALUE) {
			return null;
		}

		int globalX = chunkX * 16 + highestLocalX;
		int globalZ = chunkZ * 16 + highestLocalZ;
		return new BlockInfo(globalX, highestY, globalZ, highestBlockId, debug);
	}

	private static int getPaletteIndex(long[] data, int blockIndex, int bitsPerEntry) {
		int bitOffset = blockIndex * bitsPerEntry;
		int arrayIndex = bitOffset / 64;
		int offsetInLong = bitOffset % 64;
		if (arrayIndex >= data.length) return 0;
		long value = (data[arrayIndex] >>> offsetInLong) & ((1L << bitsPerEntry) - 1);
		return (int) value;
	}

	static class BlockInfo {
		int x, y, z;
		String blockId;
		String debug;
		BlockInfo(int x, int y, int z, String id, String dbg) {
			this.x = x; this.y = y; this.z = z; this.blockId = id; this.debug = dbg;
		}
	}

	static class Result {
		int x, y, z;
		String blockId;
		int processedFiles;
		String debugInfo;
		Result(int x, int y, int z, String id, int files, String dbg) {
			this.x = x; this.y = y; this.z = z; this.blockId = id; this.processedFiles = files; this.debugInfo = dbg;
		}
	}
}