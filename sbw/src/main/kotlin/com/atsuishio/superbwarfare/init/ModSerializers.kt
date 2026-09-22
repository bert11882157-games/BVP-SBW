package com.atsuishio.superbwarfare.init

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.network.NetworkTelemetry
import net.minecraft.nbt.NbtAccounter
import net.minecraft.network.syncher.EntityDataSerializer
import net.minecraft.world.item.ItemStack
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject

object ModSerializers {
    private const val MAX_FLAT_LIST_ELEMENTS = 4_096
    private const val MAX_GUN_DATA_ENTRIES = 64
    private const val MAX_WEAPON_NAME_CHARS = 128
    private const val MAX_GUN_DATA_NBT_BYTES = 256 * 1024
    private const val MAX_NESTED_LISTS = 512
    private const val MAX_NESTED_LIST_ELEMENTS = 512

    val REGISTRY: DeferredRegister<EntityDataSerializer<*>> =
        DeferredRegister.create(ForgeRegistries.Keys.ENTITY_DATA_SERIALIZERS, Mod.MODID)

    @JvmField
    val INT_LIST_SERIALIZER: RegistryObject<EntityDataSerializer<List<Int>>> =
        REGISTRY.register("int_list_serializer") {
            EntityDataSerializer.simple({ buf, list ->
                require(list.size <= MAX_FLAT_LIST_ELEMENTS) { "Integer entity-data list is too large" }
                val start = buf.writerIndex()
                buf.writeVarInt(list.size)
                for (v in list) {
                    buf.writeVarInt(v)
                }
                NetworkTelemetry.recordEntityDataDirty("int_list", buf.writerIndex() - start)
            }, { buf ->
                val length = readBoundedSize(buf, MAX_FLAT_LIST_ELEMENTS, "integer entity-data list")
                val list = ArrayList<Int>(length)
                repeat(length) {
                    list.add(buf.readVarInt())
                }
                list
            })
        }

    @JvmField
    val FLOAT_LIST_SERIALIZER: RegistryObject<EntityDataSerializer<List<Float>>> =
        REGISTRY.register("float_list_serializer") {
            EntityDataSerializer.simple({ buf, list ->
                require(list.size <= MAX_FLAT_LIST_ELEMENTS) { "Float entity-data list is too large" }
                val start = buf.writerIndex()
                buf.writeVarInt(list.size)
                for (v in list) {
                    require(v.isFinite()) { "Non-finite float entity data" }
                    buf.writeFloat(v)
                }
                NetworkTelemetry.recordEntityDataDirty("float_list", buf.writerIndex() - start)
            }, { buf ->
                val length = readBoundedSize(buf, MAX_FLAT_LIST_ELEMENTS, "float entity-data list")
                val list = ArrayList<Float>(length)
                repeat(length) {
                    val value = buf.readFloat()
                    require(value.isFinite()) { "Non-finite float entity data" }
                    list.add(value)
                }
                list
            })
        }

    @JvmField
    val VEHICLE_GUN_DATA_MAP_SERIALIZER: RegistryObject<EntityDataSerializer<Map<String, GunData>>> =
        REGISTRY.register("vehicle_gun_data_map_serializer") {
            EntityDataSerializer.simple({ buf, map ->
                require(map.size <= MAX_GUN_DATA_ENTRIES) { "Vehicle gun-data map is too large" }
                val start = buf.writerIndex()
                buf.writeVarInt(map.size)
                for (kv in map.entries) {
                    buf.writeUtf(kv.key, MAX_WEAPON_NAME_CHARS)
                    val tagStart = buf.writerIndex()
                    buf.writeNbt(kv.value.stack.shareTag)
                    require(buf.writerIndex() - tagStart <= MAX_GUN_DATA_NBT_BYTES) {
                        "Vehicle gun-data NBT exceeds $MAX_GUN_DATA_NBT_BYTES bytes"
                    }
                }
                NetworkTelemetry.recordEntityDataDirty("vehicle_gun_data_map", buf.writerIndex() - start)
            }, { buf ->
                val length = readBoundedSize(buf, MAX_GUN_DATA_ENTRIES, "vehicle gun-data map")
                val map = LinkedHashMap<String, GunData>(length)
                repeat(length) {
                    val weaponName = buf.readUtf(MAX_WEAPON_NAME_CHARS)

                    val tag = buf.readNbt(NbtAccounter(MAX_GUN_DATA_NBT_BYTES.toLong()))
                    val gunItemStack = ItemStack(ModItems.VEHICLE_GUN.get())
                    gunItemStack.setTag(tag)

                    map[weaponName] = GunData.from(gunItemStack)
                }
                map
            })
        }

    @JvmField
    val SHORT_LIST_LIST_SERIALIZER: RegistryObject<EntityDataSerializer<List<List<Short>>>> =
        REGISTRY.register("short_list_list_serializer") {
            EntityDataSerializer.simple({ buf, list ->
                require(list.size <= MAX_NESTED_LISTS) { "Nested short entity-data list is too large" }
                val start = buf.writerIndex()
                buf.writeVarInt(list.size)
                for (sl in list) {
                    require(sl.size <= MAX_NESTED_LIST_ELEMENTS) { "Nested short entity-data row is too large" }
                    buf.writeVarInt(sl.size)
                    sl.forEach { buf.writeShort(it.toInt()) }
                }
                NetworkTelemetry.recordEntityDataDirty("short_list_list", buf.writerIndex() - start)
            }, { buf ->
                val size = readBoundedSize(buf, MAX_NESTED_LISTS, "nested short entity-data list")
                val list = ArrayList<List<Short>>(size)
                repeat(size) {
                    val slSize = readBoundedSize(buf, MAX_NESTED_LIST_ELEMENTS, "nested short entity-data row")
                    val sl = ArrayList<Short>(slSize)
                    repeat(slSize) {
                        sl.add(buf.readShort())
                    }
                    list.add(sl)
                }
                list
            })
        }

    private fun readBoundedSize(buf: net.minecraft.network.FriendlyByteBuf, maximum: Int, label: String): Int {
        val size = buf.readVarInt()
        require(size in 0..maximum) { "$label size $size exceeds $maximum" }
        return size
    }
}
