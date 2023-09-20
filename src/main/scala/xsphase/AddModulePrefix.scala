package xsphase

import firrtl.AnnotationSeq
import firrtl.annotations._
import firrtl.ir.{Block, DefInstance, ExtModule, Module, Statement}
import firrtl.options.Phase
import firrtl.renamemap.MutableRenameMap
import firrtl.stage.FirrtlCircuitAnnotation

object PrefixingHelper {
  var prefix = "bosc_"
  def StatementsWalker(stmt:Statement):Statement = {
    stmt match {
      case s: DefInstance => s.copy(module = prefix + s.module)
      case s :Block => {
        val stmts = s.stmts.map(StatementsWalker)
        s.copy(stmts = stmts)
      }
      case other => other
    }
  }
}

class Prefixing extends Phase {
  override def prerequisites: Seq[Nothing] = Seq.empty
  override def optionalPrerequisites: Seq[Nothing] = Seq.empty
  override def optionalPrerequisiteOf: Seq[Nothing] = Seq.empty
  override def invalidates(a: Phase) = false
  private val prefix = PrefixingHelper.prefix
  private val renameMap = MutableRenameMap()
  def transform(annotations: AnnotationSeq): AnnotationSeq = {
    val prefixedAS = annotations.flatMap {
      case a: FirrtlCircuitAnnotation =>
        val mods = a.circuit.modules.map {
          case mm@Module(_, name, _, body) => {
            renameMap.record(ModuleTarget(a.circuit.main, name), ModuleTarget(prefix + a.circuit.main, prefix + name))
            val nst = PrefixingHelper.StatementsWalker(body)
            mm.copy(name = prefix + name, body = nst)
          }
          case em@ExtModule(_, name, _, defname, _) => {
            renameMap.record(ModuleTarget(a.circuit.main, name), ModuleTarget(prefix + a.circuit.main, name))
            em.copy(name = prefix + name, defname = prefix + defname)
          }
          case other => other
        }
        val nc = a.circuit.copy(modules = mods, main = prefix + a.circuit.main)
        Some(FirrtlCircuitAnnotation(nc))
      case a => Some(a)
    }
    val redirectedAS = prefixedAS.flatMap{
      a => a.update(renameMap)
    }
    redirectedAS
  }
}
