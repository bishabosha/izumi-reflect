package izumi.reflect.dottyreflection

import java.nio.charset.StandardCharsets

import apple.laf.JRSUIUtils.Tree
import izumi.reflect.internal.fundamentals.collections.IzCollections.toRich
import izumi.reflect.macrortti.LightTypeTag.ParsedLightTypeTag.SubtypeDBs
import izumi.reflect.macrortti.{LightTypeTag, LightTypeTagRef}
import izumi.reflect.macrortti.LightTypeTagRef._
import izumi.reflect.thirdparty.internal.boopickle.NoMacro.Pickler
import izumi.reflect.thirdparty.internal.boopickle.PickleImpl

import scala.quoted.Type
import scala.reflect.Selectable.reflectiveSelectable

abstract class Inspector(protected val shift: Int) extends InspectorBase {
  self =>

  // @formatter:off
  import qctx.tasty.{Type => TType, _}
  // @formatter:on

  private def next() = new Inspector(shift + 1) {
    val qctx: self.qctx.type = self.qctx
  }

  def buildTypeRef[T <: AnyKind : Type]: AbstractReference = {
    val tpe = implicitly[Type[T]]
    val uns = tpe.unseal
    log(s" -------- about to inspect ${tpe} --------")
    val v = inspectTree(uns)
    log(s" -------- done inspecting ${tpe} --------")
    v
  }

  private[dottyreflection] def inspectTType(tpe2: TType): AbstractReference = {
    tpe2 match {
      case a: AppliedType =>
        a.args match {
          case Nil =>
            asNameRef(a.tycon)
          case o =>
            // https://github.com/lampepfl/dotty/issues/8520
            val params = a.tycon.typeSymbol.typeMembers
            val zargs = a.args.zip(params)

            val args = zargs.map {
              case (tpe, defn) =>
                next().inspectToB(tpe, defn)
            }
            val nameref = asNameRef(a.tycon)
            FullReference(nameref.ref.name, args, prefix = nameref.prefix)
        }

      case l: TypeLambda =>
        val resType = next().inspectTType(l.resType)
        val paramNames = l.paramNames.map {
          LambdaParameter(_)
        }
        LightTypeTagRef.Lambda(paramNames, resType)

      case t: ParamRef =>
        asNameRef(t)

      case a: AndType =>
        IntersectionReference(flattenInspectAnd(a))

      case o: OrType =>
        UnionReference(flattenInspectOr(o))

      case r: TypeRef =>
        next().inspectSymbol(r.typeSymbol)

      case a: AnnotatedType =>
        next().inspectTType(a.underlying)

      case tb: TypeBounds => // weird thingy
        next().inspectTType(tb.hi)

      case term: TermRef =>
        asNameRef(term)

      case lazyref if lazyref.getClass.getName.contains("LazyRef") => // upstream bug seems like
        log(s"TTYPE, UNSUPPORTED: LazyRef occured $lazyref")
        NameReference("???")

      case o =>
        log(s"TTYPE, UNSUPPORTED: $o")
        throw new RuntimeException(s"TTYPE, UNSUPPORTED: ${o.getClass} - $o")
      //???

    }
  }

  private[dottyreflection] def inspectTree(uns: TypeTree): AbstractReference = {
    val symbol = uns.symbol
    val tpe2 = uns.tpe
//    logStart(s"INSPECT: $uns: ${uns.getClass}")
    if (symbol.isNoSymbol)
      inspectTType(tpe2)
    else
      inspectSymbol(symbol)
  }

  private[dottyreflection] def inspectSymbol(symbol: Symbol): AbstractReference = {
    symbol.tree match {
      case c: ClassDef =>
        asNameRefSym(symbol)
      case t: TypeDef =>
        next().inspectTree(t.rhs.asInstanceOf[TypeTree])
      case d: DefDef =>
        next().inspectTree(d.returnTpt)
      case o =>
        log(s"SYMBOL TREE, UNSUPPORTED: $o")
        throw new RuntimeException(s"SYMBOL TREE, UNSUPPORTED: ${o.getClass} - $o")
    }
  }

  private def prefixOf(symbol: Symbol): Option[AppliedReference] = {
    if (symbol.maybeOwner.isNoSymbol) {
      None
    } else {
      symbol.maybeOwner.tree match {
        case _: PackageDef =>
          None
        case o =>
          inspectSymbol(symbol.maybeOwner) match {
            case a: AppliedReference =>
              Some(a)
            case _ =>
              None
          }
      }
    }
  }

  private def inspectToB(tpe: TypeOrBounds, td: Symbol): TypeParam = {
    val variance = extractVariance(td)

    tpe match {
      case t: TypeBounds =>
        TypeParam(inspectTType(t.hi), variance)
      case t: TType =>
        TypeParam(inspectTType(t), variance)
    }
  }


  private def extractVariance(t: Symbol) = {
    if (t.flags.is(Flags.Covariant)) {
      Variance.Covariant
    } else if (t.flags.is(Flags.Contravariant)) {
      Variance.Contravariant
    } else {
      Variance.Invariant
    }
  }

  private def flattenInspectAnd(and: AndType): Set[AppliedReference] = {
    val (andTypes, otherTypes) =
      and match {
        case AndType(l@AndType(_, _), r@AndType(_, _)) =>
          (Set(l, r), Set.empty[TType])
        case AndType(l@AndType(_, _), r) =>
          (Set(l), Set(r))
        case AndType(l, r@AndType(_, _)) =>
          (Set(r), Set(l))
        case AndType(l, r) =>
          (Set.empty[AndType], Set(l, r))
      }
    val andTypeTags = andTypes flatMap flattenInspectAnd
    val otherTypeTags = otherTypes map inspectTType map {
      _.asInstanceOf[AppliedReference]
    }
    andTypeTags ++ otherTypeTags
  }

  private def flattenInspectOr(or: OrType): Set[AppliedReference] = {
    val (orTypes, otherTypes) =
      or match {
        case OrType(l@OrType(_, _), r@OrType(_, _)) =>
          (Set(l, r), Set.empty[TType])
        case OrType(l@OrType(_, _), r) =>
          (Set(l), Set(r))
        case OrType(l, r@OrType(_, _)) =>
          (Set(r), Set(l))
        case OrType(l, r) =>
          (Set.empty[OrType], Set(l, r))
      }
    val orTypeTags = orTypes flatMap flattenInspectOr
    val otherTypeTags = otherTypes map inspectTType map {
      _.asInstanceOf[AppliedReference]
    }
    orTypeTags ++ otherTypeTags
  }

  private def asNameRef(t: TType): NameReference = {
    t match {
      case ref: TypeRef =>
        asNameRefSym(ref.typeSymbol)
      case term: TermRef =>
        asNameRefSym(term.termSymbol)
      case t: ParamRef =>
        NameReference(t.binder.asInstanceOf[ {def paramNames: List[Object]}].paramNames(t.paramNum).toString)
    }
  }

  private[dottyreflection] def asNameRefSym(t: Symbol): NameReference = {
    val prefix = prefixOf(t)
    NameReference(SymName.SymTypeName(t.fullName), prefix = prefix)
  }
}

