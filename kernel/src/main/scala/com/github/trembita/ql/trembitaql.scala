package com.github.trembita.ql

import scala.annotation.tailrec
import scala.language.experimental.macros
import scala.language.higherKinds
import scala.reflect.macros.blackbox
import com.github.trembita._
import QueryBuilder._
import cats.MonadError
import cats.kernel.Monoid

/**
  * Trembita QL itself.
  * Produces a [[QueryResult]]
  * from records of type [[A]]
  * using provided query
  **/
trait trembitaql[A, G <: GroupingCriteria, T <: AggDecl, R <: AggRes, Comb] {
  def apply(
    records: Seq[A],
    queryF: QueryBuilder.Empty[A] => Query[A, G, T, R, Comb]
  ): QueryResult[A, G, AggFunc.Result[T, R, Comb]]
}

trait trembitaqlForPipeline[A,
                            G <: GroupingCriteria,
                            T <: AggDecl,
                            R <: AggRes,
                            Comb] {
  def apply[F[_], Ex <: Execution](
    pipeline: DataPipelineT[F, A, Ex],
    queryF: QueryBuilder.Empty[A] => Query[A, G, T, R, Comb]
  )(implicit F: MonadError[F, Throwable],
    monoid: Monoid[QueryResult[A, G, AggFunc.Result[T, R, Comb]]],
    ex: Ex): F[QueryResult[A, G, AggFunc.Result[T, R, Comb]]] =
    applyWithoutTopTotals(pipeline, queryF).reduce

  def applyWithoutTopTotals[F[_], Ex <: Execution](
    pipeline: DataPipelineT[F, A, Ex],
    queryF: QueryBuilder.Empty[A] => Query[A, G, T, R, Comb]
  )(implicit F: MonadError[F, Throwable],
    ex: Ex): DataPipelineT[F, QueryResult[A, G, AggFunc.Result[T, R, Comb]], Ex]
}

/**
  * Macro implementation
  **/
object trembitaql {

  /**
    * @tparam A    - type of the record
    * @tparam G    - grouping criteria type
    * @tparam T    - aggregation declaration type
    * @tparam R    - aggregation result type
    * @tparam Comb - type of the combiner
    * @return - generated code for query execution
    **/
  def impl[A: c.WeakTypeTag,
           G <: GroupingCriteria: c.WeakTypeTag,
           T <: AggDecl: c.WeakTypeTag,
           R <: AggRes: c.WeakTypeTag,
           Comb: c.WeakTypeTag](
    c: blackbox.Context
  ): c.Expr[trembitaql[A, G, T, R, Comb]] = {

    import c.universe._

    val G = weakTypeOf[G].dealias
    val T = weakTypeOf[T].dealias
    val R = weakTypeOf[R].dealias
    val A = weakTypeOf[A].dealias
    val Comb = weakTypeOf[Comb].dealias
    val gnil = typeOf[GroupingCriteria.GNil].dealias

    @tailrec def criteriasTypes(acc: List[(Type, Type)],
                                currType: Type): List[(Type, Type)] =
      currType.typeArgs match {
        case List(key, `gnil`) => ((key, gnil) :: acc).reverse
        case List(head, rest)  => criteriasTypes((head, rest) :: acc, rest)
      }

    val allCriterias = criteriasTypes(Nil, G)
    val offset = allCriterias.size - 1

    val Result = tq"QueryResult[$A, $G, AggFunc.Result[$T, $R, $Comb]]"

    /**
      * Recursively generates code
      * that groups some records of [[A]]
      * doing defined aggregations
      **/
    def groupByRec(step: Int, criteriasLeft: List[(Type, Type)]): c.Tree = {
      val idx: Int = step - 1
      val grCurr = TermName(s"group_$idx")
      val resCurr = TermName(s"res_$idx")
      val totalsCurr = TermName(s"totals_$idx")
      criteriasLeft match {
        case Nil =>
          c.abort(
            c.enclosingPosition,
            s"""
             |Unexpected end of criterias at step $step in trembitaql macro implementation
             |{
             |  A                := $A
             |  GroupingCriteria := $G
             |  AggDecl          := $T
             |  AggRes           := $R
             |  Comb             := $Comb
             |}
             |If you're sure that you're doing everything right
             |please open an issue here: https://github.com/dataroot/trembita
        """.stripMargin
          )
        case (currCriteria, `gnil`) :: Nil =>
          q"""
         val $resCurr = $grCurr.groupBy(_._1($idx)).mapValues { vs =>
           val totals = vs.foldLeft(aggF.empty) { case (acc, (_, a)) => aggF.add(acc, getT(a)) }
           (totals, sortedVs(vs.map(_._2).toList))
         }.toList match {
           case Nil => Empty[$A, $currCriteria &:: $gnil, AggFunc.Result[$T, $R, $Comb]](aggF.extract(aggF.empty))
           case Seq((key, (totals, vs))) => ~::[$A, $currCriteria, $gnil, AggFunc.Result[$T, $R, $Comb]](
             Key.Single(key),
             aggF.extract(totals),
             ##@(vs)
           )
           case multiple =>
             val sortedMultiple = orderCons(
               multiple.map { case (key, (totals, vs)) =>
                ~::[$A, $currCriteria, $gnil, AggFunc.Result[$T, $R, $Comb]](Key.Single(key), aggF.extract(totals), ##@(vs.toList))
               }
             )
             ~**[$A, $currCriteria &:: $gnil, AggFunc.Result[$T, $R, $Comb]](
               aggF.extract(
                 multiple.foldLeft(aggF.empty) { case (acc, gr) => aggF.combine(acc, gr._2._1) }
               ),
               sortedMultiple.head,
               NonEmptyList.fromListUnsafe(sortedMultiple.tail)
             )
         }
         """
        case (currGH, currGT) :: rest =>
          val grNext = TermName(s"group_$step")
          val totalsNext = TermName(s"totals_$step")
          val resNext = TermName(s"res_$step")
          q"""
           val $resCurr = $grCurr.groupBy(_._1($idx)).map { case (key, $grNext) =>
             (..${groupByRec(step + 1, rest)})
             val $totalsNext = $resNext.totals
             ~::[ $A, $currGH, $currGT, AggFunc.Result[$T, $R, $Comb] ](
               Key.Single(key), $totalsNext, $resNext
             )
           }.toList match {
             case Nil => Empty[$A, $currGH &:: $currGT, AggFunc.Result[$T, $R, $Comb]](aggF.extract(aggF.empty))
             case List(single) => single
             case multiple =>
               val sortedMultiple = orderCons( multiple )
               val totals = multiple.foldLeft(aggF.empty) { case (acc, gr) => aggF.combine(acc, gr.totals.combiner) }
               ~**[$A, $currGH &:: $currGT, AggFunc.Result[$T, $R, $Comb]](
                 aggF.extract(totals),
                 sortedMultiple.head,
                 NonEmptyList.fromListUnsafe(sortedMultiple.tail)
               )
           }
         """
      }
    }

    G.typeArgs match {
      case List(gHx, gTx) =>
        c.Expr[trembitaql[A, G, T, R, Comb]](q"""
          import QueryBuilder._, QueryResult._, cats.data.NonEmptyList, shapeless._, GroupingCriteria._, AggDecl._, AggRes._
          new trembitaql[$A, $G, $T, $R, $Comb] {
            def apply(records: Seq[$A], qb: QueryBuilder.Empty[$A] => Query[$A, $G, $T, $R, $Comb])
            : QueryResult[$A, $G, AggFunc.Result[$T, $R, $Comb]] = {
               val query: Query[$A, $G, $T, $R, $Comb] = qb(new QueryBuilder.Empty[$A])
               import query._

               def sortedVs(vs: List[$A]): List[$A] = orderRecords match {
                 case None => vs
                 case Some(orderRecF) => vs.sorted(orderRecF)
               }

               def orderCons[GH <: :@[_, _], GT <: GroupingCriteria]
               (grs: List[~::[$A, GH, GT, AggFunc.Result[$T, $R, $Comb]]])
               : List[~::[$A, GH, GT, AggFunc.Result[$T, $R, $Comb]]] = orderResults match {
                 case None => grs
                 case Some(orderResF) => grs.sortBy(_.totals.result)(orderResF)
               }

               val group_0 = orderCriterias match {
                 case None => records.filter(filterF).map(a => getG(a) → a)
                 case Some(orderCrF) => records.filter(filterF).map(a => getG(a) → a).sortBy(_._1)(orderCrF)
               }

               ${groupByRec(1, allCriterias)}
               Some(res_0)
                 .filter(gr => havingF(gr.totals.result))
                 .getOrElse(
                   Empty[$A, $G, AggFunc.Result[$T, $R, $Comb]](aggF.extract(aggF.empty))
                 )
            }
          }""")
    }
  }

  implicit def deriveQuery[A,
                           G <: GroupingCriteria,
                           T <: AggDecl,
                           R <: AggRes,
                           Comb]: trembitaql[A, G, T, R, Comb] =
    macro impl[A, G, T, R, Comb]
}

object trembitaqlForPipeline {

  /**
    * @tparam A    - type of the record
    * @tparam G    - grouping criteria type
    * @tparam T    - aggregation declaration type
    * @tparam R    - aggregation result type
    * @tparam Comb - type of the combiner
    * @return - generated code for query execution
    **/
  def impl[A: c.WeakTypeTag,
           G <: GroupingCriteria: c.WeakTypeTag,
           T <: AggDecl: c.WeakTypeTag,
           R <: AggRes: c.WeakTypeTag,
           Comb: c.WeakTypeTag](
    c: blackbox.Context
  ): c.Expr[trembitaqlForPipeline[A, G, T, R, Comb]] = {

    import c.universe._

    val G = weakTypeOf[G].dealias
    val T = weakTypeOf[T].dealias
    val R = weakTypeOf[R].dealias
    val A = weakTypeOf[A].dealias
    val Comb = weakTypeOf[Comb].dealias
    val gnil = typeOf[GroupingCriteria.GNil].dealias

    @tailrec def criteriasTypes(acc: List[(Type, Type)],
                                currType: Type): List[(Type, Type)] =
      currType.typeArgs match {
        case List(key, `gnil`) => ((key, gnil) :: acc).reverse
        case List(head, rest)  => criteriasTypes((head, rest) :: acc, rest)
      }

    val allCriterias = criteriasTypes(Nil, G)
    val offset = allCriterias.size - 1

    /**
      * Recursively generates code
      * that groups some records of [[A]]
      * doing defined aggregations
      **/
    def groupByRec(step: Int, criteriasLeft: List[(Type, Type)]): c.Tree = {
      val idx: Int = step - 1
      val grCurr = TermName(s"group_$idx")
      val resCurr = TermName(s"res_$idx")
      val totalsCurr = TermName(s"totals_$idx")
      criteriasLeft match {
        case Nil =>
          throw new Exception(s"""
             |Unexpected end of criterias at step $step in trembitaql macro implementation
             |{
             |  A                := $A
             |  GroupingCriteria := $G
             |  AggDecl          := $T
             |  AggRes           := $R
             |  Comb             := $Comb
             |}
             |If you're sure that you're doing everything right
             |please open an issue here: https://github.com/dataroot/trembita
        """.stripMargin)
        case (currCriteria, `gnil`) :: Nil =>
          q"""
         val $resCurr = $grCurr.groupBy(_._1($idx)).toVector.map { case (key, vs) =>
           val totals = vs.foldLeft(aggF.empty) { case (acc, (_, a)) => aggF.add(acc, getT(a)) }
           key -> (totals -> sortedVs( vs.map(_._2) ).toList )
         } match {
           case Vector() => Empty[$A, $currCriteria &:: $gnil, AggFunc.Result[$T, $R, $Comb]](aggF.extract(aggF.empty))
           case Vector((key, (totals, vs))) => ~::[$A, $currCriteria, $gnil, AggFunc.Result[$T, $R, $Comb]](
             Key.Single(key),
             aggF.extract(totals),
             ##@(vs)
           )
           case multiple =>
             val sortedMultiple = orderCons(
               multiple.map { case (key, (totals, vs)) =>
                ~::[$A, $currCriteria, $gnil, AggFunc.Result[$T, $R, $Comb]](Key.Single(key), aggF.extract(totals), ##@(vs.toList))
               }
             ).toList
             ~**[$A, $currCriteria &:: $gnil, AggFunc.Result[$T, $R, $Comb]](
               aggF.extract(
                 multiple.foldLeft(aggF.empty) { case (acc, gr) => aggF.combine(acc, gr._2._1) }
               ),
               sortedMultiple.head,
               NonEmptyList.fromListUnsafe(sortedMultiple.tail)
             )
         }
         """
        case (currGH, currGT) :: rest if step != 1 =>
          val grNext = TermName(s"group_$step")
          val totalsNext = TermName(s"totals_$step")
          val resNext = TermName(s"res_$step")
          q"""
           val $resCurr = $grCurr.groupBy(_._1($idx)).toVector.map { case (key, $grNext) =>
             (..${groupByRec(step + 1, rest)})
             val $totalsNext = $resNext.totals
             ~::[ $A, $currGH, $currGT, AggFunc.Result[$T, $R, $Comb] ](
               Key.Single(key), $totalsNext, $resNext
             )
           } match {
             case Vector() => Empty[$A, $currGH &:: $currGT, AggFunc.Result[$T, $R, $Comb]](aggF.extract(aggF.empty))
             case Vector(single) => single
             case multiple =>
               val sortedMultiple = orderCons( multiple ).toList
               val totals = multiple.foldLeft(aggF.empty) { case (acc, gr) => aggF.combine(acc, gr.totals.combiner) }
               ~**[$A, $currGH &:: $currGT, AggFunc.Result[$T, $R, $Comb]](
                 aggF.extract(totals),
                 sortedMultiple.head,
                 NonEmptyList.fromListUnsafe(sortedMultiple.tail)
               )
           }
         """

        case (currGH, currGT) :: rest =>
          val grNext = TermName(s"group_$step")
          val totalsNext = TermName(s"totals_$step")
          val resNext = TermName(s"res_$step")
          q"""
           val $resCurr = $grCurr.groupBy(_._1($idx)).map { case (key, $grNext) =>
             (..${groupByRec(step + 1, rest)})
             val $totalsNext = $resNext.totals
             ~::[ $A, $currGH, $currGT, AggFunc.Result[$T, $R, $Comb] ](
               Key.Single(key), $totalsNext, $resNext
             )
           }.filter(gr => havingF(gr.totals.result))
         """
      }
    }

    G.typeArgs match {
      case List(gHx, gTx) =>
        val expr = c.Expr[trembitaqlForPipeline[A, G, T, R, Comb]](q"""
          import QueryBuilder._, QueryResult._, cats.data.NonEmptyList, shapeless._, GroupingCriteria._, AggDecl._, AggRes._
          new trembitaqlForPipeline[$A, $G, $T, $R, $Comb] {
            def applyWithoutTopTotals[F[_], Ex <: Execution]
            (pipeline  : DataPipelineT[F, $A,  Ex],
             qb        : QueryBuilder.Empty[$A] => Query[$A, $G, $T, $R, $Comb])
            (implicit F: cats.MonadError[F, Throwable],
            ex: Ex)
            : DataPipelineT[F, QueryResult[$A, $G, AggFunc.Result[$T, $R, $Comb]], Ex] = {
               val query: Query[$A, $G, $T, $R, $Comb] = qb(new QueryBuilder.Empty[$A])
               import query._

               def sortedVs(vs: Vector[$A]): Vector[$A] = orderRecords match {
                 case None => vs
                 case Some(orderRecF) => vs.sorted(orderRecF)
               }

               def orderCons[GH <: :@[_, _], GT <: GroupingCriteria]
               (grs: Vector[~::[$A, GH, GT, AggFunc.Result[$T, $R, $Comb]]])
               : Vector[~::[$A, GH, GT, AggFunc.Result[$T, $R, $Comb]]] = orderResults match {
                 case None => grs
                 case Some(orderResF) => grs.sortBy(_.totals.result)(orderResF)
               }

               val group_0 = orderCriterias match {
                 case None => pipeline.filter(filterF).map(a => getG(a) → a)
                 case Some(orderCrF) => pipeline.filter(filterF).map(a => getG(a) → a).sortBy(_._1)(orderCrF, ex, F)
               }
               ${groupByRec(1, allCriterias)}
               res_0
            }
          }""")
//        println(expr)
        expr
    }
  }

  implicit def deriveQuery[A,
                           G <: GroupingCriteria,
                           T <: AggDecl,
                           R <: AggRes,
                           Comb]: trembitaqlForPipeline[A, G, T, R, Comb] =
    macro impl[A, G, T, R, Comb]
}
