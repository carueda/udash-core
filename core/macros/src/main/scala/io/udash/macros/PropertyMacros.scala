package io.udash.macros

import com.avsystem.commons.macros.AbstractMacroCommons
import com.avsystem.commons.misc.Opt

import scala.collection.mutable
import scala.reflect.macros.blackbox

class PropertyMacros(val ctx: blackbox.Context) extends AbstractMacroCommons(ctx) {
  import c.universe._

  val Package = q"_root_.io.udash.properties"
  val IsModelPropertyTemplateCls = tq"$Package.IsModelPropertyTemplate"

  val RegistrationCls = tq"$Package.Registration"
  val PropertyCreatorCls = tq"$Package.PropertyCreator"
  val SinglePropertyCreatorCls = tq"$Package.SinglePropertyCreator"
  val PropertyCreatorCompanion = q"$Package.PropertyCreator"
  val ModelPropertyCreatorCls = tq"$Package.ModelPropertyCreator"
  val MacroModelPropertyCreatorCls = tq"$Package.MacroModelPropertyCreator"

  val ReadablePropertyCls = tq"$Package.single.ReadableProperty"
  val PropertyCls = tq"$Package.single.Property"
  val DirectPropertyImplCls = tq"$Package.single.DirectPropertyImpl"
  val SeqPropertyCls = tq"$Package.seq.SeqProperty"
  val DirectSeqPropertyImplCls = tq"$Package.seq.DirectSeqPropertyImpl"
  val ModelPropertyCls = tq"$Package.model.ModelProperty"
  val ModelPropertyImplCls = tq"$Package.model.ModelPropertyImpl"
  val CastablePropertyCls = tq"$Package.single.CastableProperty"

  val PatchCls = tq"$Package.SeqProperty.Patch"

  private lazy val SeqTpe = typeOf[Seq[_]]
  private lazy val MutableSeqTpe = typeOf[scala.collection.mutable.Seq[_]]
  private lazy val topLevelSymbols = Set(typeOf[Any], typeOf[AnyRef], typeOf[AnyVal], typeOf[Product], typeOf[Equals]).map(_.typeSymbol)

  private def fixOverride(s: Symbol) =
    if (s.isTerm && s.asTerm.isOverloaded) {
      s.alternatives.filterNot(_.isSynthetic).head
    } else s

  private def withOverrides(s: Symbol) =
    s :: s.overrides.map(fixOverride)

  private def isFromTopLevelType(symbol: Symbol) =
    withOverrides(symbol).exists(topLevelSymbols contains _.owner)

  //Checks, if symbol is abstract method
  private def isAbstractMethod(symbol: Symbol): Boolean =
    symbol.isAbstract && symbol.isMethod && !symbol.asMethod.isAccessor

  //Checks, if symbol is abstract field
  private def isAbstractField(symbol: Symbol): Boolean =
    isAbstractVal(symbol) || isAbstractVar(symbol)

  //Checks, if symbol is abstract field
  private def isAbstractVal(symbol: Symbol): Boolean =
    symbol.isAbstract && symbol.isTerm && symbol.asTerm.isVal

  //Checks, if symbol is abstract field
  private def isAbstractVar(symbol: Symbol): Boolean =
    symbol.isAbstract && symbol.isTerm && symbol.asTerm.isVar

  //Checks, if symbol is method and takes any parameters
  private def takesParameters(symbol: Symbol): Boolean =
    symbol.isMethod && symbol.asMethod.paramLists.nonEmpty

  //Finds primary constructor of provided type
  private def findPrimaryConstructor(tpe: Type): MethodSymbol =
    tpe.members.collectFirst {
      case m: MethodSymbol if m.isPrimaryConstructor => m
    }.get

  //Returns filtered members without synthetic, top level and constructor of provided type
  private def filterMembers(valueType: Type) =
    valueType.members
      .filterNot(member => member.isSynthetic || isFromTopLevelType(member) || member.isConstructor || member.isType)

  private def isModelPropertyTemplate(valueType: Type): Boolean =
    c.typecheck(q"implicitly[$IsModelPropertyTemplateCls[$valueType]]", silent = true) != EmptyTree

  private def hasModelPropertyCreator(valueType: Type): Boolean =
    c.typecheck(q"implicitly[$ModelPropertyCreatorCls[$valueType]]", silent = true) != EmptyTree

  //Checks, if trait is valid ModelProperty template
  private def doesMeetTraitModelRequirements(tpe: Type): Boolean = {
    val isClass: Boolean = tpe.typeSymbol.isClass
    val isTrait = isClass && tpe.typeSymbol.asClass.isTrait
    val isNotSealedTrait = isClass && !tpe.typeSymbol.asClass.isSealed
    val isNotSeq = !(tpe <:< SeqTpe)

    val members = filterMembers(tpe)
    val unimplementableTraitMembers = members.collect {
      case s if isAbstractMethod(s) && takesParameters(s) => s
    }
    val doesNotContainVars = !members.exists {
      case s if s.isTerm && s.asTerm.isVar => true
      case _ => false
    }
    isTrait && isNotSealedTrait && isNotSeq && unimplementableTraitMembers.isEmpty && doesNotContainVars
  }

  //Checks, if method or value can be treated as Property in trait based ModelProperty
  private def doesMeetTraitModelElementRequirements(s: Symbol, signatureType: Type): Boolean =
    (isAbstractMethod(s) && !takesParameters(s)) || isAbstractVal(s) ||
      (s.isAbstract && s.isMethod && s.asMethod.isAccessor && s.asMethod.accessed == NoSymbol) // val in trait in scala 2.11

  //Returns filtered members which can be treated as Properties in trait based ModelProperty
  private def traitBasedPropertyMembers(tpe: Type): Seq[Symbol] =
    filterMembers(tpe).filter(!_.isPrivate)
      .filter(s => doesMeetTraitModelElementRequirements(s, tpe)).toSeq

  //Returns filtered members which can be treated as Properties in case class based ModelProperty
  private def classBasedPropertyMembers(tpe: Type): Seq[Symbol] =
    filterMembers(tpe)
      .filter(m => !m.isPrivate && !(tpe <:< typeOf[Tuple2[_, _]] && m.name.decodedName.toString == "swap"))
      .filter(m => m.isMethod && (m.asMethod.isParamAccessor || m.asMethod.isCaseAccessor)).toSeq

  //Checks, if tpe is a class which can be used as ModelProperty template
  private def doesMeetClassModelRequirements(tpe: Type): Boolean = {
    tpe.typeSymbol.isClass && !tpe.typeSymbol.isAbstract &&
      findPrimaryConstructor(tpe).paramLists.size == 1 &&
      findPrimaryConstructor(tpe).paramLists(0).nonEmpty &&
      findPrimaryConstructor(tpe).isPublic &&
      filterMembers(tpe)
        .filter(m => !m.isPrivate && !(tpe <:< typeOf[Tuple2[_, _]] && m.name.decodedName.toString == "swap"))
        .forall { m =>
          if (m.isMethod && m.asMethod.isAccessor && m.asMethod.accessed.isTerm) m.asMethod.accessed.asTerm.isVal
          else m.isMethod || m.asTerm.isVal
        }
  }

  private def getModelPath(tree: Tree) = tree match {
    case f@Function(_, path) => path
    case _ => c.abort(tree.pos, "Only inline lambdas supported. Please use `subProp(_.path.to.element)`.")
  }

  def isValidSubproperty(tpe: Type, name: Name): Boolean =
    traitBasedPropertyMembers(tpe).map(_.name).contains(name) || classBasedPropertyMembers(tpe).map(_.name).contains(name)

  def reifyModelPart[T: c.WeakTypeTag]: c.Tree = {
    val valueType = weakTypeOf[T]

    val isClass: Boolean = valueType.typeSymbol.isClass
    val isTrait = isClass && valueType.typeSymbol.asClass.isTrait
    val isNotSealedTrait = isClass && !valueType.typeSymbol.asClass.isSealed
    val isNotSeq = !(valueType <:< SeqTpe) && !(valueType <:< MutableSeqTpe)

    lazy val isValidModelClass: Boolean = doesMeetClassModelRequirements(valueType)

    val members = filterMembers(valueType)
    val unimplementableTraitMembers = members.collect {
      case s if isAbstractMethod(s) && takesParameters(s) => s
    }
    val doesNotContainVars = !members.exists {
      case s if s.isTerm && s.asTerm.isVar => true
      case _ => false
    }
    val isImplementableTrait = isTrait && unimplementableTraitMembers.isEmpty && doesNotContainVars

    val propertyElementsToTypeCheck = if (isTrait && isNotSealedTrait && isNotSeq) {
      traitBasedPropertyMembers(valueType)
    } else if (isValidModelClass) {
      classBasedPropertyMembers(valueType)
    } else Seq.empty

    if (isTrait && isNotSealedTrait && isNotSeq && isImplementableTrait) {
      q"""null"""
    } else if (isValidModelClass) {
        q"""null """
    } else {
      val isCC = !(isTrait && isNotSealedTrait && isNotSeq && isImplementableTrait)
      c.abort(c.enclosingPosition,
        s"""
           |The type `$valueType` does not meet model part requirements. It must be (not sealed) trait or immutable case class.
           |
           |Model part checks:
           |* for traits:
           |  * isTrait: $isTrait
           |  * isImplementableTrait: $isImplementableTrait
           |  * isNotSealedTrait: $isNotSealedTrait
           |  * isNotSeq: $isNotSeq
           |  * doesNotContainVars: $doesNotContainVars
           |  * unimplementableTraitMembers: ${
                  if (isTrait && isNotSealedTrait && isNotSeq)
                    unimplementableTraitMembers
                      .map(m => s"${m.name}: ${m.typeSignatureIn(valueType).resultType}")
                      .map(s => s"\n    - $s").mkString("")
                  else "Visible only for traits."
                }
           |* for case class:
           |  * isClass: ${valueType.typeSymbol.isClass}
           |  * isNotAbstract: ${!valueType.typeSymbol.isAbstract}
           |  * hasOneParamsListInPrimaryConstructor: ${
                isCC && findPrimaryConstructor(valueType).paramLists.size == 1
              }
           |  * primaryConstructorIsPublic: ${
                isCC && findPrimaryConstructor(valueType).isPublic
              }
           |  * members: ${
                  if (isCC)
                    if (propertyElementsToTypeCheck.nonEmpty) {
                      propertyElementsToTypeCheck
                        .map(m => s"${m.name}: ${m.typeSignatureIn(valueType).resultType} " +
                          s"-> isCaseAccessor: ${m.isMethod && m.asMethod.isCaseAccessor};"
                        ).map(s => s"\n    - $s").mkString("")
                    } else "No members found."
                  else "Visible only for case classes."
                }
           |
           |Use ModelValue.isModelValue[${propertyElementsToTypeCheck.headOption.getOrElse("T")}] to get more details about `isModelValue` check.
           |
           |""".stripMargin
      )
    }
  }

  private def generateModelProperty(tpe: Type): c.Tree = {
    def impl(members: Map[TermName, Type], getCreator: Tree): Tree = {
      q"""
        new $ModelPropertyImplCls[$tpe](prt, $PropertyCreatorCompanion.newID()) {
          override protected def initialize(): Unit = {
            ..${
              members.map {
                case (name, returnTpe) =>
                  q"""properties(${name.toString}) = implicitly[$PropertyCreatorCls[$returnTpe]].newProperty(this)"""
              }
            }
          }

          protected def internalGet: $tpe =
            $getCreator

          protected def internalSet(newValue: $tpe, withCallbacks: Boolean, force: Boolean): Unit = {
            ..${
              members.map { case (name, returnTpe) =>
                q"""
                  setSubProp(
                    getSubProperty[$returnTpe](${name.toString}),
                    if (newValue != null) newValue.$name else null.asInstanceOf[$returnTpe],
                    withCallbacks, force
                  )
                """
              }
            }
          }
        }
       """
    }

    if (doesMeetClassModelRequirements(tpe)) {
      val order = findPrimaryConstructor(tpe).paramLists.flatten.map(m => m.name.toTermName)
      val members = classBasedPropertyMembers(tpe)
        .map(m => m.asMethod.name -> m.typeSignatureIn(tpe).resultType).toMap

      impl(members,
        q"""
          new ${tpe.typeSymbol}(
            ..${
              order.map { case name =>
                val returnTpe = members(name)
                q"""getSubProperty[$returnTpe](${name.toString}).get"""
              }
            }
          )
         """
      )
    } else {
      val members = traitBasedPropertyMembers(tpe).map(method => (method.asMethod.name, method.typeSignatureIn(tpe).resultType))
      impl(members.toMap,
        q"""
          new $tpe {
            ..${
              members.map { case (name, returnTpe) =>
                q"""override val $name: $returnTpe = getSubProperty[$returnTpe](${name.toString}).get"""
              }
            }
          }
        """
      )
    }
  }

  def reifySubProp[A: c.WeakTypeTag, B: c.WeakTypeTag](f: c.Expr[A => B])(ev: c.Tree): c.Tree =
    reifySubProperty[A, B](f)

  def reifySubProperty[A: c.WeakTypeTag, B: c.WeakTypeTag](f: c.Expr[A => B]): c.Tree = {
    val model = c.prefix
    val modelPath = getModelPath(f.tree)

    def checkIfIsValidPath(tree: Tree): Boolean = tree match {
      case s@Select(next@Ident(_), t) if isValidSubproperty(next.tpe, t) =>
        true
      case s@Select(next, t) if hasModelPropertyCreator(next.tpe.widen) && isValidSubproperty(next.tpe, t) =>
        checkIfIsValidPath(next)
      case s@Select(next, t) =>
        c.abort(c.enclosingPosition,
          s"""
             |The path must consist of ModelProperties and only leaf can be a Property, ModelProperty or SeqProperty.
             | * ${next.tpe.widen} ${if (hasModelPropertyCreator(next.tpe.widen)) "is" else "is NOT"} a ModelProperty
             | * $t ${if (isValidSubproperty(next.tpe, t)) "is" else "is NOT"} a valid subproperty (abstract val/def for trait based model or constructor element for (case) class based model)
             |
             |""".stripMargin
        )
      case _ =>
        c.abort(c.enclosingPosition, s"The path must consist of ModelProperties and leaf can be a simple Property, ModelProperty or SeqProperty.")
    }

    checkIfIsValidPath(modelPath)

    def parsePath(tree: Tree, acc: List[(Select, TermName)] = List()): List[(Select, TermName)] = tree match {
      case s@Select(next, t@TermName(_)) => parsePath(next, (s, t) :: acc)
      case _ => acc
    }

    val parts = parsePath(modelPath)

    def genTree(source: List[(Select, TermName)], targetTree: Tree): Tree = source match {
      case (select, term) :: tail if select.tpe.typeConstructor =:= SeqTpe.typeConstructor =>
        q"""$targetTree.getSubProperty[${select.tpe.widen}](${term.decodedName.toString})
           .asInstanceOf[$SeqPropertyCls[${select.tpe.typeArgs.head.widen}, $PropertyCls[${select.tpe.typeArgs.head.widen}] with $CastablePropertyCls[${select.tpe.typeArgs.head.widen}]]]"""
      case (select, term) :: tail if hasModelPropertyCreator(select.tpe.widen) =>
        genTree(tail, q"""$targetTree.getSubProperty[${select.tpe.widen}](${term.decodedName.toString}).asInstanceOf[$ModelPropertyImplCls[${select.tpe.widen}]]""")
      case (select, term) :: tail =>
        q"""$targetTree.getSubProperty[${select.tpe.widen}](${term.decodedName.toString}).asInstanceOf[$PropertyCls[${select.tpe.widen}]]"""
      case Nil => targetTree
    }

    genTree(parts, q"""$model.asInstanceOf[$ModelPropertyImplCls[_]]""")
  }

  def reifyModelPropertyCreator[A: c.WeakTypeTag](ev: c.Tree): c.Tree = {
    val tpe = weakTypeOf[A]
    q"""{
      new $ModelPropertyCreatorCls[$tpe] with $PropertyCreatorCompanion.MacroGeneratedPropertyCreator {
        override def newProperty(prt: $ReadablePropertyCls[_]): $CastablePropertyCls[$tpe] = {
          implicit val ${TermName(c.freshName())}: $ModelPropertyCreatorCls[$tpe] with $PropertyCreatorCompanion.MacroGeneratedPropertyCreator = this
          ${generateModelProperty(tpe)}
        }
      }
    }"""
  }

  def reifyMacroModelPropertyCreator[A: c.WeakTypeTag](ev: c.Tree): c.Tree = {
    q"""
      new $MacroModelPropertyCreatorCls(
        ${reifyModelPropertyCreator[A](ev)}
      )
    """
  }

  def reifyPropertyCreator[A: c.WeakTypeTag]: c.Tree = {
    val tpe = weakTypeOf[A].dealias

    if (!tpe.typeSymbol.isClass) c.abort(c.enclosingPosition, s"Implicit PropertyCreator[$tpe] not found.")
    else q"new $SinglePropertyCreatorCls[$tpe]"
  }

  def checkModelPropertyTemplate[A: c.WeakTypeTag]: c.Tree = {
    val tpe = weakTypeOf[A]
    if (doesMeetTraitModelRequirements(tpe) || doesMeetClassModelRequirements(tpe)) q"new $IsModelPropertyTemplateCls[$tpe]"
    else c.abort(c.enclosingPosition,
      s"""
         |`$tpe` is not a valid ModelProperty template.
         |
         |There are two valid model bases:
         | * trait (not sealed trait) with following restrictions:
         |   * it cannot contain vars
         |   * it can contain implemented vals and defs, but they are not considered as subproperties
         |   * all abstract vals and defs (without parameters) are considered as subproperties
         | * (case) class with following restrictions:
         |   * it cannot contain vars
         |   * it can contain implemented vals and defs, but they are not considered as subproperties
         |   * it cannot have more than one parameters list in primary constructor
         |   * all elements of primary constructor are considered as subproperties
       """.stripMargin
    )
  }
}
