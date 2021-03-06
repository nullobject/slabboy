package slabboy

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

class SlabBoy extends Component {
  val io = new Bundle {
    val address = out UInt(16 bits)
    val dataIn = in UInt(8 bits)
    val dataOut = out UInt(8 bits)
    val en = out Bool
    val write = out Bool
    val halt = out Bool
  }

  val cpu = new Cpu(
    bootVector = 0x0000,
    spInit = 0xFFFE
  )

  io.address := cpu.io.address
  cpu.io.dataIn := io.dataIn
  io.dataOut := cpu.io.dataOut
  io.en := cpu.io.mreq
  io.write := cpu.io.write
  io.halt := cpu.io.halt
}

object Cpu {
  object Reg16 {
    val WZ = 1
    val BC = 2
    val DE = 3
    val HL = 4
    val SP = 5
    val PC = 6
  }

  object Reg8 {
    val A = 0; val F = 1
    val W = 2; val Z = 3
    val B = 4; val C = 5
    val D = 6; val E = 7
    val H = 8; val L = 9
    val SPH = 10; val SPL = 11
    val PCL = 12; val PCH = 13

    // auto calculate bits needed to represent register index
    def DataType = UInt(log2Up(PCH) bits)
  }

  object Flags {
    val C = 4
    val H = 5
    val N = 6
    val Z = 7
  }

  object AluOp extends SpinalEnum {
    val Nop, Add, Adc, Sub, Sbc, And, Xor, Or, Cp,
      Inc, Dec, Cpl, Ccf, Scf = newElement()
  }

  object AddrSrc extends SpinalEnum {
    val PC, HL, BC, DE, WZ, FFZ, FFC = newElement()
  }

  object AddrOp extends SpinalEnum {
    val Nop, Inc, Dec = newElement()
  }
}

class Cpu(bootVector: Int, spInit: Int) extends Component {
  import Cpu._

  val io = new Bundle {
    val address = out UInt(16 bits)
    val dataIn = in UInt(8 bits)
    val dataOut = out UInt(8 bits)
    val mreq = out Bool
    val write = out Bool
    val halt = out Bool
  }

  val address = Reg(UInt(16 bits)) init(0)
  val mreq = Reg(Bool) init(False)
  val write = Reg(Bool) init(False)
  io.mreq := mreq
  io.write := write

  // instruction register
  val ir = RegInit(U(0x00, 8 bits))

  // register file
  val registers16 = Vec(Reg(UInt(16 bits)), 7)
  // A, F, WZ, BC, DE, and HL are all initialized to zero
  for (i <- (0 until 5)) {
    registers16(i).init(0)
  }
  // SP and PC have defined init values
  registers16(Reg16.SP).init(spInit)
  registers16(Reg16.PC).init(bootVector)

  // 8-bit register vector for easy access
  val registers8 = registers16.flatMap(
    reg16 => Seq(reg16(15 downto 8), reg16(7 downto 0))
  )

  val temp = Reg(UInt(8 bits)) init(0)
  io.dataOut := temp

  val mCycle = Reg(CpuDecoder.MCycleDataType) init(0)
  val writeCycle = Reg(Bool) init(False)
  val halt = Reg(Bool) init(False)
  io.halt := halt

  val decoder = new CpuDecoder
  decoder.io.mCycle := mCycle
  decoder.io.ir := ir

  val alu = new CpuAlu
  alu.io.op := decoder.io.aluOp
  alu.io.flagsIn := registers8(Reg8.F)
  alu.io.operandA := registers8(Reg8.A)
  alu.io.operandB := temp

  switch(decoder.io.addrSrc) {
    is(AddrSrc.PC) { io.address := registers16(Reg16.PC) }
    is(AddrSrc.HL) { io.address := registers16(Reg16.HL) }
    is(AddrSrc.BC) { io.address := registers16(Reg16.BC) }
    is(AddrSrc.DE) { io.address := registers16(Reg16.DE) }
    is(AddrSrc.WZ) { io.address := registers16(Reg16.WZ) }
    is(AddrSrc.FFZ) {
      io.address(15 downto 8) := 0xFF
      io.address(7 downto 0) := registers8(Reg8.Z)
    }
    is(AddrSrc.FFC) {
      io.address(15 downto 8) := 0xFF
      io.address(7 downto 0) := registers8(Reg8.C)
    }
  }

  val tCycleFsm = new StateMachine {
    val t1State: State = new State with EntryPoint {
      onEntry {
        writeCycle := decoder.io.memWrite
        when(!decoder.io.memWrite) {
          mreq := True
        }
      }
      whenIsActive {
        mreq := False
        goto(t2State)
      }
    }
    val t2State = new State {
      whenIsActive {
        when(decoder.io.memRead) {
          temp := io.dataIn
        }.elsewhen(writeCycle) {
          temp := temp
        }.otherwise {
          ir := io.dataIn
        }

        goto(t3State)
      }
    }
    val t3State = new State {
      whenIsActive {
        when(writeCycle) {
          mreq := True
          write := True
        }

        when(decoder.io.loadOpB) {
          temp := registers8(decoder.io.opBSelect)
        }

        halt := decoder.io.nextHalt
        goto(t4State)
      }
    }
    val t4State = new State {
      whenIsActive {
        mreq := False
        write := False

        def doAddrOp(reg: UInt) {
          switch(decoder.io.addrOp) {
            is(AddrOp.Nop) { reg := reg }
            is(AddrOp.Inc) { reg := reg + 1 }
            is(AddrOp.Dec) { reg := reg - 1 }
          }
        }

        switch(decoder.io.addrSrc) {
          is(AddrSrc.PC) { doAddrOp(registers16(Reg16.PC)) }
          is(AddrSrc.HL) { doAddrOp(registers16(Reg16.HL)) }
          is(AddrSrc.BC) { doAddrOp(registers16(Reg16.BC)) }
          is(AddrSrc.DE) { doAddrOp(registers16(Reg16.DE)) }
          is(AddrSrc.FFZ) { }
        }

        when(decoder.io.store) {
          registers8(decoder.io.storeSelect) := alu.io.result
        }
        registers8(Reg8.F) := alu.io.flagsOut
        mCycle := decoder.io.nextMCycle
        when (!halt) {
          goto(t1State)
        }
      }
    }
  }
}

object CpuDecoder {
  import Cpu._

  case class MCycle(
    aluOp: SpinalEnumElement[AluOp.type],
    opBSelect: Option[Int],
    storeSelect: Option[Int],
    memRead: Boolean,
    memWrite: Boolean,
    addrSrc: SpinalEnumElement[AddrSrc.type],
    addrOp: SpinalEnumElement[AddrOp.type],
    halt: Boolean
  )

  def fetchCycle(aluOp: SpinalEnumElement[AluOp.type],
                 opBSelect: Option[Int],
                 storeSelect: Option[Int]) = {
    MCycle(aluOp, opBSelect, storeSelect, false, false,
           AddrSrc.PC, AddrOp.Inc, false)
  }

  def memReadCycle(aluOp: SpinalEnumElement[AluOp.type],
                   storeSelect: Option[Int],
                   addrSrc: SpinalEnumElement[AddrSrc.type] = AddrSrc.PC,
                   addrOp: SpinalEnumElement[AddrOp.type] = AddrOp.Nop) = {
    MCycle(aluOp, None, storeSelect, true, false, addrSrc, addrOp, false)
  }

  def memWriteCycle(aluOp: SpinalEnumElement[AluOp.type],
                    opBSelect: Option[Int],
                    storeSelect: Option[Int],
                    addrSrc: SpinalEnumElement[AddrSrc.type] = AddrSrc.PC,
                    addrOp: SpinalEnumElement[AddrOp.type] = AddrOp.Nop) = {
    MCycle(aluOp, opBSelect, storeSelect, false, true, addrSrc, addrOp, false)
  }

  // helper function for the regular op code pattern
  // used for the bulk of the arithmetic instructions
  def arithmetic8Bit(base: Int,
                     aluOp: SpinalEnumElement[AluOp.type]
                    ) : Seq[(Int, Seq[MCycle])] = {
    val store = if (aluOp == AluOp.Cp) { None } else { Some(Reg8.A) }
    Seq(
      (base + 0, Seq(fetchCycle(aluOp, Some(Reg8.B), store))),
      (base + 1, Seq(fetchCycle(aluOp, Some(Reg8.C), store))),
      (base + 2, Seq(fetchCycle(aluOp, Some(Reg8.D), store))),
      (base + 3, Seq(fetchCycle(aluOp, Some(Reg8.E), store))),
      (base + 4, Seq(fetchCycle(aluOp, Some(Reg8.H), store))),
      (base + 5, Seq(fetchCycle(aluOp, Some(Reg8.L), store))),
      (base + 6, Seq(fetchCycle(AluOp.Nop, None, None),
               memReadCycle(aluOp, store, addrSrc=AddrSrc.HL))),
      (base + 7, Seq(fetchCycle(aluOp, Some(Reg8.A), store)))
    )
  }

  // helper function for the regular op code pattern
  // used for most of the 8-bit reg-to-reg LD instructions
  def load8BitRegToReg(base: Int, dest: Int) = {
    Seq(
      (base + 0, Seq(fetchCycle(AluOp.Nop, Some(Reg8.B), Some(dest)))),
      (base + 1, Seq(fetchCycle(AluOp.Nop, Some(Reg8.C), Some(dest)))),
      (base + 2, Seq(fetchCycle(AluOp.Nop, Some(Reg8.D), Some(dest)))),
      (base + 3, Seq(fetchCycle(AluOp.Nop, Some(Reg8.E), Some(dest)))),
      (base + 4, Seq(fetchCycle(AluOp.Nop, Some(Reg8.H), Some(dest)))),
      (base + 5, Seq(fetchCycle(AluOp.Nop, Some(Reg8.L), Some(dest)))),
      (base + 6, Seq(fetchCycle(AluOp.Nop, None, None),
               memReadCycle(AluOp.Nop, Some(dest), addrSrc=AddrSrc.HL))),
      (base + 7, Seq(fetchCycle(AluOp.Nop, Some(Reg8.A), Some(dest))))
    )
  }

  val Microcode = Seq(
    // nop
    (0x00, Seq(fetchCycle(AluOp.Nop, None, None))),
    // halt
    (0x76, Seq(MCycle(AluOp.Nop, None, None, false, false,
               AddrSrc.PC, AddrOp.Nop, true)))
  ) ++
  arithmetic8Bit(0x80, AluOp.Add) ++ arithmetic8Bit(0x88, AluOp.Adc) ++
  arithmetic8Bit(0x90, AluOp.Sub) ++ arithmetic8Bit(0x98, AluOp.Sbc) ++
  arithmetic8Bit(0xA0, AluOp.And) ++ arithmetic8Bit(0xA8, AluOp.Xor) ++
  arithmetic8Bit(0xB0, AluOp.Or) ++ arithmetic8Bit(0xB8, AluOp.Cp) ++
  load8BitRegToReg(0x40, Reg8.B) ++
  load8BitRegToReg(0x48, Reg8.C) ++
  load8BitRegToReg(0x50, Reg8.D) ++
  load8BitRegToReg(0x58, Reg8.E) ++
  load8BitRegToReg(0x60, Reg8.H) ++
  load8BitRegToReg(0x68, Reg8.L) ++
  load8BitRegToReg(0x78, Reg8.A) ++
  Seq(
    // inc B
    (0x04, Seq(fetchCycle(AluOp.Inc, Some(Reg8.B), Some(Reg8.B)))),
    // inc B
    (0x0C, Seq(fetchCycle(AluOp.Inc, Some(Reg8.C), Some(Reg8.C)))),
    // inc D
    (0x14, Seq(fetchCycle(AluOp.Inc, Some(Reg8.D), Some(Reg8.D)))),
    // inc E
    (0x1C, Seq(fetchCycle(AluOp.Inc, Some(Reg8.E), Some(Reg8.E)))),
    // inc H
    (0x24, Seq(fetchCycle(AluOp.Inc, Some(Reg8.H), Some(Reg8.H)))),
    // inc L
    (0x2C, Seq(fetchCycle(AluOp.Inc, Some(Reg8.L), Some(Reg8.L)))),
    // inc (HL)
    (0x34, Seq(fetchCycle(AluOp.Nop, None, None),
               memReadCycle(AluOp.Inc, Some(Reg8.Z), addrSrc=AddrSrc.HL),
               memWriteCycle(AluOp.Nop, Some(Reg8.Z), None,
                             addrSrc=AddrSrc.HL))),
    // inc A
    (0x3C, Seq(fetchCycle(AluOp.Inc, Some(Reg8.A), Some(Reg8.A)))),
    // dec B
    (0x05, Seq(fetchCycle(AluOp.Dec, Some(Reg8.B), Some(Reg8.B)))),
    // dec C
    (0x0D, Seq(fetchCycle(AluOp.Dec, Some(Reg8.C), Some(Reg8.C)))),
    // dec D
    (0x15, Seq(fetchCycle(AluOp.Dec, Some(Reg8.D), Some(Reg8.D)))),
    // dec E
    (0x1D, Seq(fetchCycle(AluOp.Dec, Some(Reg8.E), Some(Reg8.E)))),
    // dec H
    (0x25, Seq(fetchCycle(AluOp.Dec, Some(Reg8.H), Some(Reg8.H)))),
    // dec L
    (0x2D, Seq(fetchCycle(AluOp.Dec, Some(Reg8.L), Some(Reg8.L)))),
    // dec (HL)
    (0x35, Seq(fetchCycle(AluOp.Nop, None, None),
               memReadCycle(AluOp.Dec, Some(Reg8.Z), addrSrc=AddrSrc.HL),
               memWriteCycle(AluOp.Nop, Some(Reg8.Z), None,
                             addrSrc=AddrSrc.HL))),
    // dec A
    (0x3D, Seq(fetchCycle(AluOp.Dec, Some(Reg8.A), Some(Reg8.A)))),
    // scf
    (0x37, Seq(fetchCycle(AluOp.Scf, None, None))),
    // cpl
    (0x2F, Seq(fetchCycle(AluOp.Cpl, Some(Reg8.A), Some(Reg8.A)))),
    // ccf
    (0x3F, Seq(fetchCycle(AluOp.Ccf, None, None))),
    // ld B, d8
    (0x06, Seq(fetchCycle(AluOp.Nop, None, None),
               memReadCycle(AluOp.Nop, Some(Reg8.B), addrOp=AddrOp.Inc))),
    // ld C, d8
    (0x0E, Seq(fetchCycle(AluOp.Nop, None, None),
               memReadCycle(AluOp.Nop, Some(Reg8.C), addrOp=AddrOp.Inc))),
    // ld D, d8
    (0x16, Seq(fetchCycle(AluOp.Nop, None, None),
               memReadCycle(AluOp.Nop, Some(Reg8.D), addrOp=AddrOp.Inc))),
    // ld E, d8
    (0x1E, Seq(fetchCycle(AluOp.Nop, None, None),
               memReadCycle(AluOp.Nop, Some(Reg8.E), addrOp=AddrOp.Inc))),
    // ld H, d8
    (0x26, Seq(fetchCycle(AluOp.Nop, None, None),
               memReadCycle(AluOp.Nop, Some(Reg8.H), addrOp=AddrOp.Inc))),
    // ld L, d8
    (0x2E, Seq(fetchCycle(AluOp.Nop, None, None),
               memReadCycle(AluOp.Nop, Some(Reg8.L), addrOp=AddrOp.Inc))),
    // ld A, d8
    (0x3E, Seq(fetchCycle(AluOp.Nop, None, None),
               memReadCycle(AluOp.Nop, Some(Reg8.A), addrOp=AddrOp.Inc))),
    // ld (hl), b
    (0x70, Seq(fetchCycle(AluOp.Nop, Some(Reg8.B), None),
               memWriteCycle(AluOp.Nop, None, None, addrSrc=AddrSrc.HL))),
    // ld (hl), c
    (0x71, Seq(fetchCycle(AluOp.Nop, Some(Reg8.C), None),
               memWriteCycle(AluOp.Nop, None, None, addrSrc=AddrSrc.HL))),
    // ld (hl), d
    (0x72, Seq(fetchCycle(AluOp.Nop, Some(Reg8.D), None),
               memWriteCycle(AluOp.Nop, None, None, addrSrc=AddrSrc.HL))),
    // ld (hl), e
    (0x73, Seq(fetchCycle(AluOp.Nop, Some(Reg8.E), None),
               memWriteCycle(AluOp.Nop, None, None, addrSrc=AddrSrc.HL))),
    // ld (hl), h
    (0x74, Seq(fetchCycle(AluOp.Nop, Some(Reg8.H), None),
               memWriteCycle(AluOp.Nop, None, None, addrSrc=AddrSrc.HL))),
    // ld (hl), l
    (0x75, Seq(fetchCycle(AluOp.Nop, Some(Reg8.L), None),
               memWriteCycle(AluOp.Nop, None, None, addrSrc=AddrSrc.HL))),
    // ld (hl), a
    (0x77, Seq(fetchCycle(AluOp.Nop, Some(Reg8.A), None),
               memWriteCycle(AluOp.Nop, None, None, addrSrc=AddrSrc.HL))),
    // ld (bc), a
    (0x02, Seq(fetchCycle(AluOp.Nop, Some(Reg8.A), None),
               memWriteCycle(AluOp.Nop, None, None, addrSrc=AddrSrc.BC))),
    // ld (de), a
    (0x12, Seq(fetchCycle(AluOp.Nop, Some(Reg8.A), None),
               memWriteCycle(AluOp.Nop, None, None, addrSrc=AddrSrc.DE))),
    // ld a, (bc)
    (0x0A, Seq(fetchCycle(AluOp.Nop, None, None),
             memReadCycle(AluOp.Nop, Some(Reg8.A), addrSrc=AddrSrc.BC))),
    // ld a, (de)
    (0x1A, Seq(fetchCycle(AluOp.Nop, None, None),
             memReadCycle(AluOp.Nop, Some(Reg8.A), addrSrc=AddrSrc.DE))),
    // ld (hl), d8
    (0x36, Seq(fetchCycle(AluOp.Nop, None, None),
             memReadCycle(AluOp.Nop, None, addrOp=AddrOp.Inc),
             memWriteCycle(AluOp.Nop, None, None, addrSrc=AddrSrc.HL))),
    // ld (hl+), a
    (0x22, Seq(fetchCycle(AluOp.Nop, Some(Reg8.A), None),
               memWriteCycle(AluOp.Nop, None, None,
                             addrSrc=AddrSrc.HL, addrOp=AddrOp.Inc))),
    // ld (hl-), a
    (0x32, Seq(fetchCycle(AluOp.Nop, Some(Reg8.A), None),
               memWriteCycle(AluOp.Nop, None, None,
                             addrSrc=AddrSrc.HL, addrOp=AddrOp.Dec))),
    // ld a, (hl+)
    (0x2A, Seq(fetchCycle(AluOp.Nop, None, None),
               memReadCycle(AluOp.Nop, Some(Reg8.A),
                            addrSrc=AddrSrc.HL, addrOp=AddrOp.Inc))),
    // ld a, (hl-)
    (0x3A, Seq(fetchCycle(AluOp.Nop, None, None),
               memReadCycle(AluOp.Nop, Some(Reg8.A),
                            addrSrc=AddrSrc.HL, addrOp=AddrOp.Dec))),
    // ldh (a8), a
    (0xE0, Seq(fetchCycle(AluOp.Nop, None, None),
             memReadCycle(AluOp.Nop, Some(Reg8.Z), addrOp=AddrOp.Inc),
             memWriteCycle(AluOp.Nop, Some(Reg8.A), None,
                           addrSrc=AddrSrc.FFZ, addrOp=AddrOp.Nop))),
    // ldh a, (a8)
    (0xF0, Seq(fetchCycle(AluOp.Nop, None, None),
             memReadCycle(AluOp.Nop, Some(Reg8.Z), addrOp=AddrOp.Inc),
             memReadCycle(AluOp.Nop, Some(Reg8.A),
                          addrSrc=AddrSrc.FFZ, addrOp=AddrOp.Nop))),
    // ld (c), a
    (0xE2, Seq(fetchCycle(AluOp.Nop, None, None),
             memWriteCycle(AluOp.Nop, Some(Reg8.A), None,
                           addrSrc=AddrSrc.FFC, addrOp=AddrOp.Nop))),
    // ld a, (c)
    (0xF2, Seq(fetchCycle(AluOp.Nop, None, None),
             memReadCycle(AluOp.Nop, Some(Reg8.A),
                          addrSrc=AddrSrc.FFC, addrOp=AddrOp.Nop))),
    // ld (a16), a
    (0xEA, Seq(fetchCycle(AluOp.Nop, None, None),
             memReadCycle(AluOp.Nop, Some(Reg8.Z), addrOp=AddrOp.Inc),
             memReadCycle(AluOp.Nop, Some(Reg8.W), addrOp=AddrOp.Inc),
             memWriteCycle(AluOp.Nop, Some(Reg8.A), None,
                           addrSrc=AddrSrc.WZ, addrOp=AddrOp.Nop))),
    // ldh a, (a16)
    (0xFA, Seq(fetchCycle(AluOp.Nop, None, None),
             memReadCycle(AluOp.Nop, Some(Reg8.Z), addrOp=AddrOp.Inc),
             memReadCycle(AluOp.Nop, Some(Reg8.W), addrOp=AddrOp.Inc),
             memReadCycle(AluOp.Nop, Some(Reg8.A),
                          addrSrc=AddrSrc.WZ, addrOp=AddrOp.Nop)))
  )

  val DefaultCycle = Microcode(0)._2(0)

  val MaxMCycles = Microcode.map(code => code._2.length).reduceLeft(_ max _ )
  def MCycleDataType = UInt(log2Up(MaxMCycles) bits)
}

class CpuDecoder extends Component {
  import Cpu._
  import CpuDecoder._

  val io = new Bundle {
    val mCycle = in(MCycleDataType)
    val nextMCycle = out(MCycleDataType)
    val ir = in UInt(8 bits)
    val aluOp = out(AluOp())
    val opBSelect = out(Reg8.DataType)
    val loadOpB = out Bool
    val storeSelect = out(Reg8.DataType)
    val store = out Bool
    val memRead = out Bool
    val memWrite = out Bool
    val addrSrc = out(AddrSrc())
    val addrOp = out(AddrOp())
    val nextHalt = out Bool
  }

  def decodeCycle(cycle: MCycle, nextCycle: Option[MCycle]=None) = {
    io.aluOp := cycle.aluOp
    cycle.opBSelect match {
      case Some(x) => {
        io.opBSelect := x
        io.loadOpB := True
      }
      case None => {
        io.opBSelect := 0
        io.loadOpB := False
      }
    }
    cycle.storeSelect match {
      case Some(x) => {
        io.storeSelect := x
        io.store := True
      }
      case None => {
        io.storeSelect := 0
        io.store := False
      }
    }
    io.memRead := Bool(cycle.memRead)
    io.addrSrc := cycle.addrSrc
    io.addrOp := cycle.addrOp
    io.nextHalt := Bool(cycle.halt)

    // some signals have to be set here for the next cycle
    nextCycle match {
      case Some(nCycle) => {
        io.memWrite := Bool(nCycle.memWrite)
      }
      case None => {
        io.memWrite := False
      }
    }
  }

  // default to NOP
  decodeCycle(DefaultCycle)
  io.nextMCycle := 0

  // track assigned op-codes to prevent repeats
  var assignedOpCodes = collection.mutable.HashMap[Int, Boolean]()
    .withDefaultValue(false)

  // decode microcode instructions
  for(icode <- Microcode) {
    if (assignedOpCodes(icode._1)) {
      throw new RuntimeException(
        s"Op-code 0x${icode._1.toHexString} already assigned!")
    }
    assignedOpCodes(icode._1) = true
    when(io.ir === icode._1) {
      for((cycle, i) <- icode._2.zipWithIndex) {
        when(io.mCycle === i) {
          if(i == icode._2.length - 1) {
            decodeCycle(cycle)
            io.nextMCycle := 0
          } else {
            decodeCycle(cycle, Some(icode._2(i + 1)))
            io.nextMCycle := io.mCycle + 1
          }
        }
      }
    }
  }
}

class CpuAlu extends Component {
  import Cpu.AluOp

  val io = new Bundle {
    val op = in(AluOp())
    val flagsIn = in UInt(8 bits)
    val flagsOut = out UInt(8 bits)
    val operandA = in UInt(8 bits)
    val operandB = in UInt(8 bits)
    val result = out UInt(8 bits)
  }

  // use 9-bits internally so the carry bit is easily available
  val wideResult = UInt(9 bits)
  io.result := wideResult(7 downto 0)
  val wideOpA = io.operandA.resize(9 bits)
  val wideOpB = io.operandB.resize(9 bits)

  // grab carry bits
  val carry = wideResult(8)
  // Z80 has half-carry and half-borrow bits as well
  val halfCarry = (
    wideResult.asBits(4) &&
    wideResult.asBits(3 downto 0) === B(0, 4 bits)
  )
  val halfBorrow = (
    !wideResult.asBits(4) &&
    wideResult.asBits(3 downto 0) === B(0xF, 4 bits)
  )

  // by default, pass flags through
  io.flagsOut := io.flagsIn

  // helper for optionally setting or resetting flags
  def setFlags(c: Bool, h: Bool, n: Bool) = {
    io.flagsOut(Cpu.Flags.C) := c
    io.flagsOut(Cpu.Flags.H) := h
    io.flagsOut(Cpu.Flags.N) := n
    io.flagsOut(Cpu.Flags.Z) := (wideResult(7 downto 0) === 0)
  }

  switch(io.op) {
    is(AluOp.Nop) {
      wideResult := wideOpB
    }
    is(AluOp.Add) {
      wideResult := wideOpA + wideOpB
      setFlags(carry, halfCarry, False)
    }
    is(AluOp.Adc) {
      wideResult := wideOpA + wideOpB + io.flagsIn(Cpu.Flags.C).asUInt
      setFlags(carry, halfCarry, False)
    }
    is(AluOp.Sub) {
      wideResult := wideOpA - wideOpB
      setFlags(carry, halfBorrow, True)
    }
    is(AluOp.Sbc) {
      wideResult := wideOpA - wideOpB - io.flagsIn(Cpu.Flags.C).asUInt
      setFlags(carry, halfBorrow, True)
    }
    is(AluOp.And) {
      wideResult := wideOpA & wideOpB
      setFlags(False, True, False)
    }
    is(AluOp.Xor) {
      wideResult := wideOpA ^ wideOpB
      setFlags(False, False, False)
    }
    is(AluOp.Or) {
      wideResult := wideOpA | wideOpB
      setFlags(False, False, False)
    }
    is(AluOp.Cp) {
      wideResult := wideOpA - wideOpB
      setFlags(!carry, !halfCarry, True)
    }
    is(AluOp.Inc) {
      wideResult := wideOpB + 1
      setFlags(io.flagsIn(Cpu.Flags.C), halfCarry, False)
    }
    is(AluOp.Dec) {
      wideResult := wideOpB - 1
      setFlags(io.flagsIn(Cpu.Flags.C), halfBorrow, True)
    }
    is(AluOp.Cpl) {
      wideResult := ~wideOpB
      io.flagsOut(Cpu.Flags.H) := True
      io.flagsOut(Cpu.Flags.N) := True
    }
    is(AluOp.Ccf) {
      wideResult := wideOpB
      io.flagsOut(Cpu.Flags.C) := ~io.flagsIn(Cpu.Flags.C)
      io.flagsOut(Cpu.Flags.H) := False
      io.flagsOut(Cpu.Flags.N) := False
    }
    is(AluOp.Scf) {
      wideResult := wideOpB
      io.flagsOut(Cpu.Flags.C) := True
      io.flagsOut(Cpu.Flags.H) := False
      io.flagsOut(Cpu.Flags.N) := False
    }
  }
}

object TopLevelVerilog {
  def main(args: Array[String]) {
    SpinalConfig().generateVerilog(new SlabBoy)
  }
}
