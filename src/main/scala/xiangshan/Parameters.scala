/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan

import org.chipsalliance.cde.config.{Field, Parameters}
import chisel3._
import chisel3.util._
import xiangshan.backend.execute.exublock.ExuParameters
import xiangshan.cache.DCacheParameters
import xiangshan.cache.prefetch._
import xiangshan.frontend.{BasePredictor, BranchPredictionResp, FTB, FakePredictor, FauFTB, ITTage, RAS, Tage, Tage_SC}
import xiangshan.frontend.icache.ICacheParameters
import xiangshan.cache.mmu.{L2TLBParameters, TLBParameters}
import freechips.rocketchip.diplomacy.AddressSet
import system.SoCParamsKey
import huancun._
import coupledL2._
import huancun.debug._
import xiangshan.mem.prefetch.{PrefetcherParams, SMSParams}

import scala.math.{max, min}
import xiangshan.vector.VectorParameters
import xs.utils.perf.DebugOptionsKey

case object XSTileKey extends Field[Seq[XSCoreParameters]]

case object XSCoreParamsKey extends Field[XSCoreParameters]

case class XSCoreParameters
(
  HasPrefetch: Boolean = false,
  HartId: Int = 0,
  XLEN: Int = 64,
  HasMExtension: Boolean = true,
  HasCExtension: Boolean = true,
  HasDiv: Boolean = true,
  HasICache: Boolean = true,
  HasDCache: Boolean = true,
  AddrBits: Int = 64,
  VAddrBits: Int = 39,
  HasFPU: Boolean = true,
  HasCustomCSRCacheOp: Boolean = true,
  FetchWidth: Int = 8,
  AsidLength: Int = 16,
  EnableBPU: Boolean = true,
  EnableBPD: Boolean = true,
  EnableRAS: Boolean = true,
  EnableLB: Boolean = false,
  EnableLoop: Boolean = true,
  EnableSC: Boolean = true,
  EnbaleTlbDebug: Boolean = false,
  EnableJal: Boolean = false,
  EnableFauFTB: Boolean = true,
  UbtbGHRLength: Int = 4,
  // HistoryLength: Int = 512,
  EnableGHistDiff: Boolean = true,
  UbtbSize: Int = 256,
  FtbSize: Int = 2048,
  RasSize: Int = 32,
  CacheLineSize: Int = 512,
  FtbWays: Int = 4,
  hasMbist:Boolean = true,
  hasShareBus:Boolean = false,
  bootAddress:Long = 0x10000000L,
  TageTableInfos: Seq[Tuple3[Int,Int,Int]] =
  //       Sets  Hist   Tag
    // Seq(( 2048,    2,    8),
    //     ( 2048,    9,    8),
    //     ( 2048,   13,    8),
    //     ( 2048,   20,    8),
    //     ( 2048,   26,    8),
    //     ( 2048,   44,    8),
    //     ( 2048,   73,    8),
    //     ( 2048,  256,    8)),
    Seq(( 4096,    8,    8),
        ( 4096,   13,    8),
        ( 4096,   32,    8),
        ( 4096,  119,    8)),
  ITTageTableInfos: Seq[Tuple3[Int,Int,Int]] =
  //      Sets  Hist   Tag
    Seq(( 256,    4,    9),
        ( 256,    8,    9),
        ( 512,   13,    9),
        ( 512,   16,    9),
        ( 512,   32,    9)),
  SCNRows: Int = 512,
  SCNTables: Int = 4,
  SCCtrBits: Int = 6,
  SCHistLens: Seq[Int] = Seq(0, 4, 10, 16),
  numBr: Int = 1,
  branchPredictor: Function3[BranchPredictionResp, Parameters, String, Tuple2[Seq[BasePredictor], BranchPredictionResp]] =
    ((resp_in: BranchPredictionResp, p: Parameters, parentName:String) => {
      val ftb = Module(new FTB(parentName = parentName + "ftb_")(p))
      val ubtb =Module(new FauFTB()(p))
      // val bim = Module(new BIM()(p))
      val tage = Module(new Tage_SC(parentName = parentName + "tage_")(p))
      val ras = Module(new RAS()(p))
      val ittage = Module(new ITTage(parentName = parentName + "ittage_")(p))
      val preds = Seq(ubtb, tage, ftb, ittage, ras)
      preds.map(_.io := DontCare)

      // ubtb.io.resp_in(0)  := resp_in
      // bim.io.resp_in(0)   := ubtb.io.resp
      // btb.io.resp_in(0)   := bim.io.resp
      // tage.io.resp_in(0)  := btb.io.resp
      // loop.io.resp_in(0)  := tage.io.resp
      ubtb.io.in.bits.resp_in(0) := resp_in
      tage.io.in.bits.resp_in(0) := ubtb.io.out
      ftb.io.in.bits.resp_in(0)  := tage.io.out
      ittage.io.in.bits.resp_in(0)  := ftb.io.out
      ras.io.in.bits.resp_in(0) := ittage.io.out

      (preds, ras.io.out)
    }),
  IBufSize: Int = 48,
  DecodeWidth: Int = 4,
  RenameWidth: Int = 4,
  CommitWidth: Int = 6,
  FtqSize: Int = 64,
  EnableLoadFastWakeUp: Boolean = true, // NOTE: not supported now, make it false
  NRPhyRegs: Int = 128,
  LoadQueueSize: Int = 80,
  LoadQueueNWriteBanks: Int = 8,
  StoreQueueSize: Int = 64,
  StoreQueueNWriteBanks: Int = 8,
  RobSize: Int = 192,
  intRsDepth:Int = 32,
  fpRsDepth:Int = 32,
  memRsDepth:Int = 48,
  rsBankNum:Int = 4,
  exuParameters: ExuParameters = ExuParameters(),
  // TODO: replace Coupled L2
  // prefetcher: Option[PrefetcherParams] = Some(SMSParams()),
  prefetcher: Option[PrefetcherParams] = None,
  Csr_PfCtrlBits: Int = 16,
  LoadPipelineWidth: Int = 2,
  StorePipelineWidth: Int = 2,
  StoreBufferSize: Int = 16,
  EnsbufferWidth: Int = 2,
  StoreBufferThreshold: Int = 7,
  EnableLoadToLoadForward: Boolean = false,
  EnableFastForward: Boolean = false,
  EnableLdVioCheckAfterReset: Boolean = true,
  EnableSoftPrefetchAfterReset: Boolean = true,
  EnableCacheErrorAfterReset: Boolean = true,
  EnablePTWPreferCache: Boolean = true,
  EnableAccurateLoadError: Boolean = true,
  MMUAsidLen: Int = 16, // max is 16, 0 is not supported now
  UseOneDtlb: Boolean = false,
  itlbParameters: TLBParameters = TLBParameters(
    name = "itlb",
    fetchi = true,
    useDmode = false,
    sameCycle = false,
    missSameCycle = true,
    normalNWays = 32,
    normalReplacer = Some("plru"),
    superNWays = 4,
    superReplacer = Some("plru"),
    shouldBlock = true
  ),
  OnedtlbParams: TLBParameters = TLBParameters(
    name = "tlb_ld_st",
    normalNSets = 64,
    normalNWays = 1,
    normalAssociative = "sa",
    normalReplacer = Some("setplru"),
    superNWays = 16,
    normalAsVictim = true,
    outReplace = false,
    partialStaticPMP = true,
    saveLevel = true
  ),
  ldtlbParameters: TLBParameters = TLBParameters(
    name = "ldtlb",
    normalNSets = 128,
    normalNWays = 1,
    normalAssociative = "sa",
    normalReplacer = Some("setplru"),
    superNWays = 8,
    normalAsVictim = true,
    outReplace = false,
    partialStaticPMP = true,
    saveLevel = true
  ),
  sttlbParameters: TLBParameters = TLBParameters(
    name = "sttlb",
    normalNSets = 128,
    normalNWays = 1,
    normalAssociative = "sa",
    normalReplacer = Some("setplru"),
    superNWays = 8,
    normalAsVictim = true,
    outReplace = false,
    partialStaticPMP = true,
    saveLevel = true
  ),
  refillBothTlb: Boolean = false,
  btlbParameters: TLBParameters = TLBParameters(
    name = "btlb",
    normalNSets = 1,
    normalNWays = 64,
    superNWays = 4,
  ),
  l2tlbParameters: L2TLBParameters = L2TLBParameters(),
  NumPerfCounters: Int = 16,
  icacheParameters: ICacheParameters = ICacheParameters(
    tagECC = None,
    dataECC = None,
    replacer = Some("setplru"),
    nMissEntries = 2,
    nProbeEntries = 2,
    nPrefetchEntries = 2,
    hasPrefetch = false,
  ),
  dcacheParametersOpt: Option[DCacheParameters] = Some(DCacheParameters(
    tagECC = Some("secded"),
    dataECC = Some("secded"),
    replacer = Some("setplru"),
    nMissEntries = 16,
    nProbeEntries = 8,
    nReleaseEntries = 18
  )),
  L2CacheParamsOpt: Option[L2Param] = Some(L2Param(
    name = "l2",
    // level = 2,
    ways = 8,
    sets = 1024,// default 512KB L2
    // hasShareBus = true,
    prefetch = Some(coupledL2.prefetch.PrefetchReceiverParams())
  )),
  L2NBanks: Int = 1,
  usePTWRepeater: Boolean = false,
  softPTW: Boolean = false, // dpi-c debug only

  //vector
  hasVector: Boolean = true,
  vectorParameters: VectorParameters = VectorParameters()
){
  val allHistLens: Seq[Int] = SCHistLens ++ ITTageTableInfos.map(_._2) ++ TageTableInfos.map(_._2) :+ UbtbGHRLength
  val HistoryLength: Int = allHistLens.max + numBr * FtqSize + 9 // 256 for the predictor configs now
  val maxRsEntryNum: Int = Seq(fpRsDepth, intRsDepth, memRsDepth).max
}

trait HasXSParameter {

  implicit val p: Parameters

  val PAddrBits = p(SoCParamsKey).PAddrBits // PAddrBits is Phyical Memory addr bits

  val coreParams = p(XSCoreParamsKey)
  val env = p(DebugOptionsKey)

  val XLEN = coreParams.XLEN
  val minFLen = 32
  val fLen = 64
  def xLen = XLEN

  val HasMExtension = coreParams.HasMExtension
  val HasCExtension = coreParams.HasCExtension
  val HasDiv = coreParams.HasDiv
  val HasIcache = coreParams.HasICache
  val HasDcache = coreParams.HasDCache
  val AddrBits = coreParams.AddrBits // AddrBits is used in some cases
  val VAddrBits = coreParams.VAddrBits // VAddrBits is Virtual Memory addr bits
  val AsidLength = coreParams.AsidLength
  val AddrBytes = AddrBits / 8 // unused
  val DataBits = XLEN
  val DataBytes = DataBits / 8
  val HasFPU = coreParams.HasFPU
  val HasCustomCSRCacheOp = coreParams.HasCustomCSRCacheOp
  val FetchWidth = coreParams.FetchWidth
  val PredictWidth = FetchWidth * (if (HasCExtension) 2 else 1)
  val EnableBPU = coreParams.EnableBPU
  val EnableBPD = coreParams.EnableBPD // enable backing predictor(like Tage) in BPUStage3
  val EnableRAS = coreParams.EnableRAS
  val EnableLB = coreParams.EnableLB
  val EnableLoop = coreParams.EnableLoop
  val EnableSC = coreParams.EnableSC
  val EnbaleTlbDebug = coreParams.EnbaleTlbDebug
  val HistoryLength = coreParams.HistoryLength
  val EnableGHistDiff = coreParams.EnableGHistDiff
  val UbtbGHRLength = coreParams.UbtbGHRLength
  val UbtbSize = coreParams.UbtbSize
  val EnableFauFTB = coreParams.EnableFauFTB
  val FtbSize = coreParams.FtbSize
  val FtbWays = coreParams.FtbWays
  val RasSize = coreParams.RasSize
  val bootAddress = coreParams.bootAddress

  def getBPDComponents(resp_in: BranchPredictionResp, p: Parameters, parentName:String = "Unknown") = {
    coreParams.branchPredictor(resp_in, p, parentName)
  }
  val numBr = coreParams.numBr
  val TageTableInfos = coreParams.TageTableInfos
  val TageBanks = coreParams.numBr
  val SCNRows = coreParams.SCNRows
  val SCCtrBits = coreParams.SCCtrBits
  val SCHistLens = coreParams.SCHistLens
  val SCNTables = coreParams.SCNTables

  val SCTableInfos = Seq.fill(SCNTables)((SCNRows, SCCtrBits)) zip SCHistLens map {
    case ((n, cb), h) => (n, cb, h)
  }
  val ITTageTableInfos = coreParams.ITTageTableInfos
  type FoldedHistoryInfo = Tuple2[Int, Int]
  val foldedGHistInfos =
    (TageTableInfos.map{ case (nRows, h, t) =>
      if (h > 0)
        Set((h, min(log2Ceil(nRows/numBr), h)), (h, min(h, t)), (h, min(h, t-1)))
      else
        Set[FoldedHistoryInfo]()
    }.reduce(_++_).toSet ++
    SCTableInfos.map{ case (nRows, _, h) =>
      if (h > 0)
        Set((h, min(log2Ceil(nRows/TageBanks), h)))
      else
        Set[FoldedHistoryInfo]()
    }.reduce(_++_).toSet ++
    ITTageTableInfos.map{ case (nRows, h, t) =>
      if (h > 0)
        Set((h, min(log2Ceil(nRows), h)), (h, min(h, t)), (h, min(h, t-1)))
      else
        Set[FoldedHistoryInfo]()
    }.reduce(_++_) ++
      Set[FoldedHistoryInfo]((UbtbGHRLength, log2Ceil(UbtbSize)))
    ).toList

  val CacheLineSize = coreParams.CacheLineSize
  val CacheLineHalfWord = CacheLineSize / 16
  val ExtHistoryLength = HistoryLength + 64
  val IBufSize = coreParams.IBufSize
  val DecodeWidth = coreParams.DecodeWidth
  val RenameWidth = coreParams.RenameWidth
  val CommitWidth = coreParams.CommitWidth
  val FtqSize = coreParams.FtqSize
  val EnableLoadFastWakeUp = coreParams.EnableLoadFastWakeUp
  val NRPhyRegs = coreParams.NRPhyRegs

  val RobSize = coreParams.RobSize
  val IntRefCounterWidth = log2Ceil(RobSize + 1)
  val LoadQueueSize = coreParams.LoadQueueSize
  val LoadQueueNWriteBanks = coreParams.LoadQueueNWriteBanks
  val StoreQueueSize = coreParams.StoreQueueSize
  val StoreQueueNWriteBanks = coreParams.StoreQueueNWriteBanks
  val exuParameters = coreParams.exuParameters
  val LoadPipelineWidth = coreParams.LoadPipelineWidth
  val StorePipelineWidth = coreParams.StorePipelineWidth
  val StoreBufferSize = coreParams.StoreBufferSize
  val StoreBufferThreshold = coreParams.StoreBufferThreshold
  val EnsbufferWidth = coreParams.EnsbufferWidth
  val EnableLoadToLoadForward = coreParams.EnableLoadToLoadForward
  val EnableFastForward = coreParams.EnableFastForward
  val EnableLdVioCheckAfterReset = coreParams.EnableLdVioCheckAfterReset
  val EnableSoftPrefetchAfterReset = coreParams.EnableSoftPrefetchAfterReset
  val EnableCacheErrorAfterReset = coreParams.EnableCacheErrorAfterReset
  val EnablePTWPreferCache = coreParams.EnablePTWPreferCache
  val EnableAccurateLoadError = coreParams.EnableAccurateLoadError
  val asidLen = coreParams.MMUAsidLen
  val BTLBWidth = coreParams.LoadPipelineWidth + coreParams.StorePipelineWidth
  val refillBothTlb = coreParams.refillBothTlb
  val itlbParams = coreParams.itlbParameters
  val ldtlbParams = coreParams.ldtlbParameters
  val ld_tlb_ports = if(coreParams.prefetcher.nonEmpty) 3 else 2
  val sttlbParams = coreParams.sttlbParameters
  val btlbParams = coreParams.btlbParameters
  val l2tlbParams = coreParams.l2tlbParameters
  val NumPerfCounters = coreParams.NumPerfCounters
  val UseOneDtlb = coreParams.UseOneDtlb
  val OnedtlbParams = coreParams.OnedtlbParams
  val Csr_PfCtrlBits = coreParams.Csr_PfCtrlBits

  val instBytes = if (HasCExtension) 2 else 4
  val instOffsetBits = log2Ceil(instBytes)

  val icacheParameters = coreParams.icacheParameters
  val dcacheParameters = coreParams.dcacheParametersOpt.getOrElse(DCacheParameters())

  val hasVector = coreParams.hasVector
  val vectorParameters = coreParams.vectorParameters
  
  val PhyRegIdxWidth = max(log2Up(NRPhyRegs), log2Up(vectorParameters.vPhyRegIdxWidth))

  // dcache block cacheline when lr for LRSCCycles - LRSCBackOff cycles
  // for constrained LR/SC loop
  val LRSCCycles = 64
  // for lr storm
  val LRSCBackOff = 8

  // cache hierarchy configurations
  val l1BusDataWidth = 256

  // load violation predict
  val ResetTimeMax2Pow = 20 //1078576
  val ResetTimeMin2Pow = 10 //1024
  // wait table parameters
  val WaitTableSize = 1024
  val MemPredPCWidth = log2Up(WaitTableSize)
  val LWTUse2BitCounter = true
  // store set parameters
  val SSITSize = WaitTableSize
  val LFSTSize = 32
  val SSIDWidth = log2Up(LFSTSize)
  val LFSTWidth = 4
  val StoreSetEnable = true // LWT will be disabled if SS is enabled

  val loadUnitNum = coreParams.exuParameters.LduCnt

  val LpvLength = 5

  val PCntIncrStep: Int = 6
  val numPCntPtw: Int = 19
  val numPCntL2: Int = coreParams.L2CacheParamsOpt.get.getPCntAll

  val numCSRPCntFrontend = 8
  val numCSRPCntCtrl     = 8
  val numCSRPCntLsu      = 8
  val numCSRPCntHc       = 5
  val printEventCoding   = true
  // Parameters for Sdtrig extension
  protected val TriggerNum = 10
  protected val TriggerChainMaxLength = 2
}
