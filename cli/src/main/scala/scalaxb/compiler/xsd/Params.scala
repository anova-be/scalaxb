/*
 * Copyright (c) 2010 e.e d3si9n
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
 
package scalaxb.compiler.xsd

import scalaxb.compiler.Log
import scala.collection.mutable
import scalaxb.compiler.Module.camelCase

sealed abstract class Cardinality
case object Optional extends Cardinality { override def toString: String = "Optional" }
case object Single extends Cardinality { override def toString: String = "Single" }
case object Multiple extends Cardinality { override def toString: String = "Multiple" }

trait Params extends Lookup {
  private val logger = Log.forName("xsd.Params")
  val ATTRS_PARAM = "attributes"
  val anyNumbers: mutable.Map[AnyDecl, Int] = mutable.Map()
  
  case class Occurrence(minOccurs: Int, maxOccurs: Int, nillable: Boolean) {
    def toSingle: Occurrence = copy(minOccurs = 1, maxOccurs = 1)
  }

  def toCardinality(minOccurs: Int, maxOccurs: Int): Cardinality =
    if (maxOccurs > 1) Multiple
    else if (minOccurs == 0) Optional
    else Single
    
  def toCardinality(occurrence: Occurrence): Cardinality =
    toCardinality(occurrence.minOccurs, occurrence.maxOccurs)
  
  def toCardinality(attr: AttributeDecl): Cardinality = {
    val minOccurs = if (attr.use == RequiredUse || 
        attr.fixedValue.isDefined || attr.defaultValue.isDefined) 1
      else 0
    toCardinality(minOccurs, 1)
  }
  
  case class Param(namespace: Option[String],
    name: String,
    typeSymbol: XsTypeSymbol,
    cardinality: Cardinality,
    nillable: Boolean,
    global: Boolean,
    qualified: Boolean,
    attribute: Boolean) {

    def baseTypeName: String = buildTypeName(typeSymbol)

    def shortBaseTypeName: String = buildTypeName(typeSymbol, true)

    def baseTypeDefaultValue: String = buildBaseTypeDefaultValue(typeSymbol)

    def singleTypeName: String =
      if (nillable) "Option[" + baseTypeName + "]"
      else baseTypeName

    def typeName: String = cardinality match {
      case Single => singleTypeName
      case Optional => "Option[" + singleTypeName + "]"
      case Multiple => "Seq[" + singleTypeName + "]"
    }

    def singleTypeAnnotation: String = {

      val fieldName = s""""${name}""""

      val typeInfo = typeSymbol match {
        case ReferenceTypeSymbol(decl: ComplexTypeDecl) => s"/*fetched $decl - ${name}*/"
        case ReferenceTypeSymbol(decl: SimpleTypeDecl)  => {
          logger.info(s"fetched ReferenceTypeSymbol(decl: SimpleTypeDecl) -> decl = $decl")
          logger.info(s" -> decl.name       = ${decl.name}")
          logger.info(s" -> decl.content    = ${decl.content}")
          logger.info(s" -> decl.family     = ${decl.family}")
          logger.info(s" -> decl.annotation = ${decl.annotation}")
          logger.info(s" -> typeName        = ${context.typeNames(decl)}")

          decl.content match {
            case SimpTypRestrictionDecl(base: XsTypeSymbol, facets: List[Facetable[_]]) => {
              logger.info(s"fetched SimpTypRestrictionDecl($base)")
              s"@xmlTypeAdapter(classOf[${context.typeNames(decl)}Adapter])"
            }
            case _ => s"/*fetched $decl*/"
          }
        }
        case XsDataRecord(member) => s"@xmlElementRef"
        case _ => s"/*${typeSymbol} - ${name}*/"
      }

      typeSymbol match {
        case XsDataRecord(member) => s"@xmlElementRef"
        case _ => s"""@${if (attribute) "xmlAttribute" else "xmlElement"}(required=${(!nillable).toString}, name=${fieldName}) ${typeInfo}"""
      }
    }

    def typeAnnotation: String = cardinality match {
      case Single => singleTypeAnnotation
      case Optional => s"@xmlTypeAdapter(classOf[${shortBaseTypeName}OptionAdapter])"
      case Multiple => singleTypeAnnotation // s"TODO: Seq[$singleTypeName]Annotation"
    }

    def toParamName: String = makeParamName(name, typeSymbol match {
      case XsLongAttribute | XsAnyAttribute => false
      case x if attribute => true
      case _ => false
    })

    def toTraitScalaCode(doMutable: Boolean): String = s"$typeAnnotation ${if (doMutable) "var " else ""}$toParamName: $typeName"

    def toScalaCode_possiblyMutable: String = toScalaCode(config.generateMutable)

    def toScalaCode(doMutable: Boolean): String =
      toTraitScalaCode(doMutable) + (cardinality match {
        case Single if typeSymbol == XsLongAttribute || typeSymbol == XsAnyAttribute => " = Map.empty"
        case Optional => " = None"
        case Multiple => " = Nil"
        case Single if nillable => " = None"
        case _ => ""
      })


    def toScalaCode_defaultValue: String = typeDefaultValue

    def typeDefaultValue: String = cardinality match {
      case Single if typeSymbol == XsLongAttribute || typeSymbol == XsAnyAttribute => "Map.empty"
      case Single => singleTypeDefaultValue
      case Optional => "None"
      case Multiple => "Nil"
    }

    def singleTypeDefaultValue: String = {
      logger.info(s"default value for $baseTypeName: $typeSymbol = $baseTypeDefaultValue")
      baseTypeDefaultValue
    }

    def map(f: String => String): Param = this.copy(name = f(name))
  }

  
  def buildParam(decl: Decl): Param = decl match {
    case elem: ElemDecl => buildParam(elem)
    case attr: AttributeDecl => buildParam(attr)
    case any: AnyAttributeDecl => buildParam(any)
    case group: AttributeGroupDecl => buildParam(group)
    case _ => sys.error("Params#buildParam: unsupported delcaration " + decl.toString)
  }
  
  def buildParam(elem: ElemDecl): Param = {
    val typeSymbol = if (isSubstitutionGroup(elem)) buildSubstitionGroupSymbol(elem.typeSymbol)
      else elem.typeSymbol match {
        case ReferenceTypeSymbol(decl: ComplexTypeDecl) =>
          if (compositorWrapper.contains(decl)) buildCompositorSymbol(compositorWrapper(decl), elem.typeSymbol)
          else elem.typeSymbol
        case _ => elem.typeSymbol
      }
    val nillable = elem.nillable getOrElse { false }
    val retval = typeSymbol match {
      case AnyType(symbol) if nillable =>
        Param(elem.namespace, elem.name, XsNillableAny, toCardinality(elem.minOccurs, elem.maxOccurs), false, false, false, false)
      case XsLongAttribute =>
        Param(elem.namespace, elem.name, typeSymbol, toCardinality(elem.minOccurs, elem.maxOccurs), nillable, false, false, true)
      case _ =>
        Param(elem.namespace, elem.name, typeSymbol, toCardinality(elem.minOccurs, elem.maxOccurs), nillable, elem.global, elem.qualified, false)
    }
    logger.debug("buildParam:  " + retval.toString)
    retval
  }
  
  def buildParam(attr: AttributeDecl): Param = {
    // uncomment for original behaviour (attr parameters are prefixed)
    // val name = if (!attr.global) attr.name else makePrefix(attr.namespace, context) + attr.name
    val name = attr.name
    
    val retval = Param(attr.namespace, name, attr.typeSymbol, toCardinality(attr), false, false, false, true)
    logger.debug("buildParam:  " + retval.toString)
    retval
  }
  
  def buildParam(group: AttributeGroupDecl): Param = {
    val retval = Param(group.namespace, group.name,
      new AttributeGroupSymbol(group.namespace, group.name), Single, false, false, false, true)
    logger.debug("buildParam:  " + retval.toString)
    retval    
  }
  
  def buildSubstitionGroupSymbol(typeSymbol: XsTypeSymbol): XsTypeSymbol =
    XsDataRecord(typeSymbol)
  
  def buildParam(any: AnyAttributeDecl): Param =
    Param(None, ATTRS_PARAM, XsAnyAttribute, Single, false, false, false, true)
  
  def buildCompositorSymbol(compositor: HasParticle, typeSymbol: XsTypeSymbol): XsTypeSymbol =
    compositor match {
      case ref: GroupRef =>
        buildCompositorSymbol(buildGroup(ref), typeSymbol)
      case group: GroupDecl =>
        val primary = primaryCompositor(group)
        val compositorRef = buildCompositorRef(primary, 0)
        buildCompositorSymbol(primaryCompositor(group), compositorRef.typeSymbol)
      case seq: SequenceDecl => typeSymbol
      case _ => XsDataRecord(typeSymbol)    
    }
  
  /// called by makeGroup
  def buildParam(compositor: HasParticle): Param =
    Param(None, "arg1", buildCompositorSymbol(compositor, buildCompositorRef(compositor, 0).typeSymbol),
      toCardinality(compositor.minOccurs, compositor.maxOccurs), false, false, false, false)
  
  def primaryCompositor(group: GroupDecl): HasParticle =
    if (group.particles.size == 1) group.particles(0) match {
      case seq: SequenceDecl    => 
        if (containsSingleChoice(seq)) singleChoice(seq)
        else seq
      case choice: ChoiceDecl   => choice
      case all: AllDecl         => all
      case p => sys.error("Params#primaryCompositor: unexpected particle type: " + p.getClass.getName)
    }
    else sys.error("Params#primaryCompositor: group must contain one content model: " + group)

  // context.compositorNames contains the definition of GroupDecl,
  // while particle GroupDecl may differ in cardinality.
  def groupTypeName(group: GroupDecl) =
    makeTypeName(context.compositorNames(groups(group.namespace, group.name)))
  
  def buildOccurrence(particle: Particle): Occurrence = particle match {
    case compositor: HasParticle => buildOccurrence(compositor)
    case elem: ElemDecl => Occurrence(elem.minOccurs, elem.maxOccurs, elem.nillable getOrElse {false})
    case ref: ElemRef   => Occurrence(ref.minOccurs, ref.maxOccurs,
      (ref.nillable getOrElse {false}) || (buildElement(ref).nillable getOrElse {false}))
    case any: AnyDecl   => Occurrence(any.minOccurs, any.maxOccurs, false)
  }
  
  def buildOccurrence(compos: HasParticle): Occurrence = compos match {
    case ref: GroupRef =>
      val o = buildOccurrence(buildGroup(ref))
      // nillability of primary compositor doesn not transfer to group or group refs
      Occurrence(math.min(ref.minOccurs, o.minOccurs), math.max(ref.maxOccurs, o.maxOccurs), false)
    case group: GroupDecl =>
      val o = buildOccurrence(primaryCompositor(group))
      // nillability of primary compositor doesn not transfer to group or group refs
      Occurrence(math.min(group.minOccurs, o.minOccurs), math.max(group.maxOccurs, o.maxOccurs), false)
    case choice: ChoiceDecl =>
      val particleOccurences = choice.particles map {buildOccurrence}
      val minOccurs = (choice.minOccurs :: particleOccurences.map(_.minOccurs)).min
      val maxOccurs = (choice.maxOccurs :: particleOccurences.map(_.maxOccurs)).max
      val nillable = choice.particles exists {
        case elem: ElemDecl => elem.nillable getOrElse {false}
        case ref: ElemRef =>
          if (ref.nillable getOrElse {false}) true
          else buildElement(ref).nillable getOrElse {false}
        case _ => false
      }
      Occurrence(minOccurs, maxOccurs, nillable)
    case _ =>
      val minOccurs = if (isEmptyCompositor(compos)) 0
                      else compos.minOccurs
      Occurrence(minOccurs, compos.maxOccurs, false)
  }

  def isEmptyCompositor(compos: HasParticle): Boolean = compos match {
    case ref: GroupRef => isEmptyCompositor(buildGroup(ref))
    case group: GroupDecl => isEmptyCompositor(primaryCompositor(group))
    case choice: ChoiceDecl =>
      choice.particles forall {
        case compositor2: HasParticle => isEmptyCompositor(compositor2)
        case _ => false
      }
    case _ => compos.particles.isEmpty
  }
  
  def mergeOccurrence(lhs: Occurrence, rhs: Occurrence): Occurrence =
    Occurrence(math.min(lhs.minOccurs, rhs.minOccurs),
      math.max(lhs.maxOccurs, rhs.maxOccurs),
      lhs.nillable || rhs.nillable)
  
  def buildLongAllRef(all: AllDecl) =
    ElemDecl(Some(INTERNAL_NAMESPACE), "all", XsLongAll, None, None, 1, 1)
  
  def buildLongAttributeRef =
    ElemDecl(Some(INTERNAL_NAMESPACE), ATTRS_PARAM, XsLongAttribute, None, None, 1, 1)
  
  def buildAnyRef(any: AnyDecl) = {
    val anyNumber = anyNumbers.getOrElseUpdate(any, anyNumbers.size + 1)
    val name = if (anyNumber <= 1) "any"
      else "any" + anyNumber
    ElemDecl(Some(INTERNAL_NAMESPACE), name, XsWildcard(any.namespaceConstraint), None, None, any.minOccurs, any.maxOccurs)
  }
    
  def buildCompositorRef(compositor: HasParticle, index: Int): ElemDecl =
    buildCompositorRef(
      compositor match {
        case ref: GroupRef => buildGroup(ref)
        case _ => compositor
      },
      compositor match {
        // overriding nillable because nillable options are handled elsewhere.
        case choice: ChoiceDecl => buildOccurrence(compositor).copy(nillable = false)
        case _ => buildOccurrence(compositor)
      },
      index)
  
  def buildCompositorRef(compositor: HasParticle, occurrence: Occurrence, index: Int): ElemDecl = {
    val ns = compositor.namespace
    val (typeName, name) = compositor match {
      case group: GroupDecl =>
        val tn = makeTypeName(context.compositorNames(primaryCompositor(group)))
        (groupTypeName(group), camelCase(tn) + (index + 1).toString)
      case _ =>
        val tn = makeTypeName(context.compositorNames(compositor))
        (tn, tn.toLowerCase)
    }

    val symbol = ReferenceTypeSymbol(ns, typeName)
    val decl = ComplexTypeDecl(ns, symbol.localPart, List(symbol.name),
      false, false, ComplexContentDecl.empty, Nil, None)

    compositorWrapper(decl) = compositor

    symbol.decl = decl
    context.typeNames(decl) = typeName

    logger.debug("buildCompositorRef: " + ns + " " + typeName)
    
    ElemDecl(ns, name, symbol, None, None,
      occurrence.minOccurs, occurrence.maxOccurs, Some(occurrence.nillable), false, false, None, None)
  }
  
  def buildChoiceTypeName(decl: ComplexTypeDecl, choice: ChoiceDecl,
      shortLocal: Boolean): String = 
    if (choice.particles.size < 1) "scalaxb.DataRecord[Any]"
    else {
      val firstParticle = choice.particles(0)
      
      def particleType(particle: Particle) = particle match {
        case elem: ElemDecl => Some(elem.typeSymbol)
        case ref: ElemRef => Some(buildElement(ref).typeSymbol)
        case _ => None
      }
      
      def sameType: Option[XsTypeSymbol] = {
        val firstType = particleType(firstParticle)
        if (firstType.isEmpty) None
        else if (choice.particles forall { particleType(_) == firstType }) firstType
        else None
      }
      
      def isOptionDescendant(particle: Particle): Boolean = particle match {
        case elem: ElemDecl =>
          elem.typeSymbol match {
            case ReferenceTypeSymbol(decl: ComplexTypeDecl) => true
            case _ => false
          }
        case ref: ElemRef =>
          buildElement(ref).typeSymbol match {
            case ReferenceTypeSymbol(decl: ComplexTypeDecl) => true
            case _ => false
          }
        case c: ChoiceDecl => c.particles forall { isOptionDescendant }
        case seq: SequenceDecl => true
        case _ => false
      }
      
      val member = sameType match {
        case Some(AnyType(x)) => "Any"
        case Some(x) => buildTypeName(x)
        case None =>
          if (!containsForeignType(choice) &&
              (choice.particles forall { isOptionDescendant }) ) buildTypeName(decl, shortLocal)
          else "Any"
      }
      if (buildOccurrence(choice).nillable) "Option[" + member + "]" // "scalaxb.DataRecord[Option[" + member + "]]"
      else member // "scalaxb.DataRecord[" + member + "]"
    }

  def buildChoiceTypeDefaultValue(decl: ComplexTypeDecl, choice: ChoiceDecl,
                          shortLocal: Boolean): String =
    if (choice.particles.size < 1) "/* TODO convert this */ DataRecord.apply(Any)" // "scalaxb.DataRecord[Any]"
    else {
      val firstParticle = choice.particles.head

      def particleType(particle: Particle) = particle match {
        case elem: ElemDecl => Some(elem.typeSymbol)
        case ref: ElemRef => Some(buildElement(ref).typeSymbol)
        case _ => None
      }

      def sameType: Option[XsTypeSymbol] = {
        val firstType = particleType(firstParticle)
        if (firstType.isEmpty) None
        else if (choice.particles forall { particleType(_) == firstType }) firstType
        else None
      }

      def isOptionDescendant(particle: Particle): Boolean = particle match {
        case elem: ElemDecl =>
          elem.typeSymbol match {
            case ReferenceTypeSymbol(decl: ComplexTypeDecl) => true
            case _ => false
          }
        case ref: ElemRef =>
          buildElement(ref).typeSymbol match {
            case ReferenceTypeSymbol(decl: ComplexTypeDecl) => true
            case _ => false
          }
        case c: ChoiceDecl => c.particles forall { isOptionDescendant }
        case seq: SequenceDecl => true
        case _ => false
      }

      val member = sameType match {
        case Some(AnyType(x)) => "/* TODO convert this */ Any"
        case Some(x) => "/* buildChoiceTypeDefaultValue#448 */" + buildBaseTypeDefaultValue(x) // buildTypeName(x)
        case None =>
          if (!containsForeignType(choice) &&
            (choice.particles forall { isOptionDescendant }) ) "/* buildChoiceTypeDefaultValue#451 */" + buildTypeNameDefaultValue(decl, shortLocal) + ".apply"
          else "Any"
      }
      if (buildOccurrence(choice).nillable) "None" /*"DataRecord.apply(None)"*/ // "scalaxb.DataRecord[Option[" + member + "]]"
      else s"/* buildChoiceTypeDefaultValue#456 */ $member" /*"DataRecord.apply(new " + member + "())"*/ // "scalaxb.DataRecord[" + member + "]"
    }
}
