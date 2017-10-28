package com.soywiz.kpspemu.format

import com.soywiz.korio.ds.lmapOf
import com.soywiz.korio.error.invalidOp
import com.soywiz.korio.stream.*
import com.soywiz.korio.util.UByteArray

class Elf private constructor(val stream: SyncStream) {
	companion object {
		fun read(stream: SyncStream) = Elf(stream).apply { read() }
	}

	lateinit var header: Header
	lateinit var programHeadersStream: SyncStream
	lateinit var sectionHeadersStream: SyncStream
	lateinit var programHeaders: List<ElfProgramHeader>
	lateinit var sectionHeaders: List<ElfSectionHeader>
	lateinit var sectionHeaderStringTable: ElfSectionHeader
	lateinit var stringTableStream: SyncStream
	lateinit var sectionHeadersByName: MutableMap<String, ElfSectionHeader>

	private fun read() {
		header = Header(stream)

		programHeadersStream = stream.sliceWithSize(header.programHeaderOffset.toLong(), (header.programHeaderCount * header.programHeaderEntrySize).toLong())
		sectionHeadersStream = stream.sliceWithSize(header.sectionHeaderOffset.toLong(), (header.sectionHeaderCount * header.sectionHeaderEntrySize).toLong())

		programHeaders = (0 until header.programHeaderCount).map { ElfProgramHeader(programHeadersStream) }
		sectionHeaders = (0 until header.sectionHeaderCount).map { ElfSectionHeader(sectionHeadersStream) }

		sectionHeaderStringTable = sectionHeaders[header.sectionHeaderStringTable]
		stringTableStream = getSectionHeaderFileStream(sectionHeaderStringTable)

		sectionHeadersByName = lmapOf<String, ElfSectionHeader>()
		for (sectionHeader in sectionHeaders) {
			val name = this.getStringFromStringTable(sectionHeader.nameOffset)
			sectionHeader.name = name
			if (sectionHeader.type != ElfSectionHeaderType.Null) {
				sectionHeader.stream = this.getSectionHeaderFileStream(sectionHeader)
			}
			sectionHeadersByName[name] = sectionHeader
		}
	}

	private fun getSectionHeaderFileStream(sectionHeader: ElfSectionHeader): SyncStream {
		//console.log('::' + sectionHeader.type + ' ; ' + sectionHeader.offset + ' ; ' + sectionHeader.size);
		return when (sectionHeader.type) {
			ElfSectionHeaderType.NoBits, ElfSectionHeaderType.Null -> this.stream.sliceWithSize(0, 0)
			else -> this.stream.sliceWithSize(sectionHeader.offset.toLong(), sectionHeader.size.toLong())
		}
	}

	private fun getStringFromStringTable(index: Int): String {
		this.stringTableStream.position = index.toLong()
		return this.stringTableStream.readStringz()
	}

	data class Header(
		val magic: String,
		val clazz: Int,
		val data: Int,
		val idVersion: Int,
		val padding: UByteArray,
		val type: Int,
		val machine: Int,
		val version: Int,
		val entryPoint: Int,
		val programHeaderOffset: Int,
		val sectionHeaderOffset: Int,
		val flags: Int,
		val elfHeaderSize: Int,
		val programHeaderEntrySize: Int,
		val programHeaderCount: Int,
		val sectionHeaderEntrySize: Int,
		val sectionHeaderCount: Int,
		val sectionHeaderStringTable: Int
	) {
		companion object {
			operator fun invoke(s: SyncStream): Header = s.run {
				return Header(
					magic = readStringz(4).apply { if (this != "\u007FELF") invalidOp("Not an ELF file") },
					clazz = readU8(),
					data = readU8(),
					idVersion = readU8(),
					padding = readUByteArray(9),
					type = readU16_le(),
					machine = readU16_le(),
					version = readS32_le(),
					entryPoint = readS32_le(),
					programHeaderOffset = readS32_le(),
					sectionHeaderOffset = readS32_le(),
					flags = readS32_le(),
					elfHeaderSize = readS16_le(),
					programHeaderEntrySize = readS16_le(),
					programHeaderCount = readU16_le(),
					sectionHeaderEntrySize = readU16_le(),
					sectionHeaderCount = readU16_le(),
					sectionHeaderStringTable = readU16_le()
				)
			}
		}

		val hasValidMagic: Boolean get() = this.magic == "\u007FELF"
		val hasValidMachine: Boolean get() = this.machine == ElfMachine.ALLEGREX.id
		val hasValidType: Boolean get() = listOf(ElfType.Executable.id, ElfType.Prx.id).contains(this.type)

		init {
			if (!hasValidMagic) invalidOp("Not an ELF file")
			if (!hasValidMachine) invalidOp("Not a PSP ELF file")
			if (!hasValidType) invalidOp("Not a executable or a Prx but has type $type")
		}
	}
}

open class BaseEnum<T : BaseEnum.Id>(val values: Array<T>) {
	interface Id {
		val id: Int
	}

	val BY_ID = values.map { it.id to it }.toMap()
	operator fun get(index: Int) = BY_ID[index] ?: invalidOp("Can't find index $index in class")
	operator fun invoke(index: Int) = this[index]
}

data class ElfProgramHeaderType(override val id: Int) : BaseEnum.Id {
	companion object {
		val NoLoad = 0
		val Load = 1
		val Reloc1 = 0x700000A0
		val Reloc2 = 0x700000A1
	}
}

enum class ElfSectionHeaderType(override val id: Int) : BaseEnum.Id {
	Null(0),
	ProgramBits(1),
	SYMTAB(2),
	STRTAB(3),
	RELA(4),
	HASH(5),
	DYNAMIC(6),
	NOTE(7),
	NoBits(8),
	Relocation(9),
	SHLIB(10),
	DYNSYM(11),

	LOPROC(0x70000000), HIPROC(0x7FFFFFFF),
	LOUSER(0x80000000.toInt()), HIUSER(0xFFFFFFFF.toInt()),

	PrxRelocation(LOPROC.id or 0xA0),
	PrxRelocation_FW5(LOPROC.id or 0xA1);

	companion object : BaseEnum<ElfSectionHeaderType>(values())
}

enum class ElfSectionHeaderFlags(override val id: Int) : BaseEnum.Id {
	None(0),
	Write(1),
	Allocate(2),
	Execute(4);

	companion object : BaseEnum<ElfSectionHeaderFlags>(values())
}

data class ElfProgramHeaderFlags(val id: Int) {
	companion object {
		val Executable = 0x1
		// Note: demo PRX's were found to be not writable
		val Writable = 0x2
		val Readable = 0x4
	}
}

enum class ElfType(override val id: Int) : BaseEnum.Id {
	Executable(0x0002),
	Prx(0xFFA0);

	companion object : BaseEnum<ElfType>(values())
}

enum class ElfMachine(override val id: Int) : BaseEnum.Id {
	ALLEGREX(8);

	companion object : BaseEnum<ElfMachine>(values())
}

enum class ElfPspModuleFlags(override val id: Int) : BaseEnum.Id { // ushort
	User(0x0000),
	Kernel(0x1000);

	companion object : BaseEnum<ElfPspModuleFlags>(values())
}

enum class ElfPspLibFlags(override val id: Int) : BaseEnum.Id { // ushort
	DirectJump(0x0001),
	Syscall(0x4000),
	SysLib(0x8000);

	companion object : BaseEnum<ElfPspLibFlags>(values())
}

enum class ElfPspModuleNids(override val id: Int) : BaseEnum.Id {  // uint
	MODULE_INFO(0xF01D73A7.toInt()),
	MODULE_BOOTSTART(0xD3744BE0.toInt()),
	MODULE_REBOOT_BEFORE(0x2F064FA6),
	MODULE_START(0xD632ACDB.toInt()),
	MODULE_START_THREAD_PARAMETER(0x0F7C276C),
	MODULE_STOP(0xCEE8593C.toInt()),
	MODULE_STOP_THREAD_PARAMETER(0xCF0CC697.toInt());

	companion object : BaseEnum<ElfPspModuleNids>(values())
}


enum class ElfRelocType(override val id: Int) : BaseEnum.Id {
	None(0),
	Mips16(1),
	Mips32(2),
	MipsRel32(3),
	Mips26(4),
	MipsHi16(5),
	MipsLo16(6),
	MipsGpRel16(7),
	MipsLiteral(8),
	MipsGot16(9),
	MipsPc16(10),
	MipsCall16(11),
	MipsGpRel32(12),
	StopRelocation(0xFF);

	companion object : BaseEnum<ElfRelocType>(values())
}

class ElfReloc(val pointerAddress: Int, val info: Int) {
	val pointeeSectionHeaderBase: Int get() = (this.info ushr 16) and 0xFF
	val pointerSectionHeaderBase: Int get() = (this.info ushr 8) and 0xFF
	val type: ElfRelocType get() = ElfRelocType[((this.info ushr 0) and 0xFF)]

	companion object {
		operator fun invoke(s: SyncStream): ElfReloc = s.run {
			ElfReloc(
				pointerAddress = s.readS32_le(),
				info = s.readS32_le())
		}
	}
}

data class ElfProgramHeader(
	val type: ElfProgramHeaderType,
	val offset: Int,
	val virtualAddress: Int,
	val psysicalAddress: Int,
	val fileSize: Int,
	val memorySize: Int,
	val flags: ElfProgramHeaderFlags, // ElfProgramHeaderFlags
	val alignment: Int
) {
	companion object {
		operator fun invoke(s: SyncStream): ElfProgramHeader = s.run {
			ElfProgramHeader(
				type = ElfProgramHeaderType(readS32_le()),
				offset = readS32_le(),
				virtualAddress = readS32_le(),
				psysicalAddress = readS32_le(),
				fileSize = readS32_le(),
				memorySize = readS32_le(),
				flags = ElfProgramHeaderFlags(readS32_le()),
				alignment = readS32_le()
			)
		}
	}
}

data class ElfSectionHeader(
	val nameOffset: Int,
	var name: String,
	val type: ElfSectionHeaderType,
	val flags: ElfSectionHeaderFlags,
	val address: Int,
	val offset: Int,
	val size: Int,
	val link: Int,
	val info: Int,
	val addressAlign: Int,
	val entitySize: Int
) {
	lateinit var stream: SyncStream

	companion object {
		operator fun invoke(s: SyncStream): ElfSectionHeader = s.run {
			ElfSectionHeader(
				nameOffset = s.readS32_le(),
				name = "...",
				type = ElfSectionHeaderType[s.readS32_le()],
				flags = ElfSectionHeaderFlags[s.readS32_le()],
				address = s.readS32_le(),
				offset = s.readS32_le(),
				size = s.readS32_le(),
				link = s.readS32_le(),
				info = s.readS32_le(),
				addressAlign = s.readS32_le(),
				entitySize = s.readS32_le()
			)
		}
	}
}