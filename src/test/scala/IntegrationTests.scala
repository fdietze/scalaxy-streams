package scalaxy.streams

package test

import org.junit._
import org.junit.Assert._
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

case class IntegrationTest(name: String, source: String, expectedMessages: CompilerMessages)

object IntegrationTests
{
  def msgs(streamDescriptions: String*)
          (implicit strategy: scalaxy.streams.OptimizationStrategy) =
    CompilerMessages(
      infos = streamDescriptions.map(Optimizations.optimizedStreamMessage(_, strategy)).toList)

  def potentialSideEffectMsgs(symFullNames: String*)
                             (implicit strategy: scalaxy.streams.OptimizationStrategy) =
    symFullNames.map(symFullName =>
      s"[Scalaxy] Potential side effect could cause issues with ${strategy.name} optimization strategy: Reference to $symFullName").toList

  def data(implicit strategy: scalaxy.streams.OptimizationStrategy)
          : List[IntegrationTest] = List[(String, CompilerMessages)](

    // """{ object Foo { def doit(args: Array[String]) = args.length } ; Foo.doit(Array("1")) }"""
    //   -> CompilerMessages(),

    // "(1 to 10).collect({ case x if x < 5 => x + 1 })"
    //   -> msgs("Range.collect -> IndexedSeq")

    // TODO investigate performance:
    // col.filter(v => (v % 2) == 0).map(_ * 2).toArray.toSeq
    // (0 until n).dropWhile(x => x < n / 2).toSeq
    // (0 until n).filter(v => (v % 2) == 0).map(_ * 2).toArray.toSeq

    """
      List(1, 2, 3, 4)
        .toIterator
        .map(_ + 1)
        .filter(_ % 2 == 0)
        .map(_ * 10)
        .toList
    """
      -> msgs("List.toIterator.map.filter.map -> List"),

    "List(1, 2, 3).toIterator.toList.toArray"
      -> msgs("List.toIterator.toList -> Array"),

    "Iterator(1, 2, 3).toList"
      -> msgs("Iterator.toList -> List"),

    "scala.scalajs.js.Array(1, 2, 3).map(_ + 2)"
      -> msgs("js.Array.map -> js.Array"),

    "scala.scalajs.js.Array(1, 2, 3).foreach(println)"
      -> msgs("js.Array.foreach"),

    "for ((a, b) <- Array(null, (1, 2))) yield (a + b)"
      -> msgs("Array.withFilter.map -> Array"),

    "for ((a, b) <- Some[(Int, Int)](null)) yield (a + b)"
      -> msgs("Some.withFilter.map -> Option"),

    """
      val list = List(null, (1, 2))
      for ((a, b) <- list) yield (a + b)
    """
      -> msgs("List.withFilter.map -> List"),

    "(0 to 10).filter(v => (v % 2) == 0).map(_ * 2).toArray.toSeq"
      -> msgs("Range.filter.map.toArray -> ArrayOps"),

    """
      class Foo {
        var col = for (i <- 0 to 10 by 2) yield (() => (i * 3))
        val res = col.map(_())
      }
      new Foo().res
    """
      -> msgs("Range.map -> IndexedSeq"),

    // Reduced from scala.tools.nsc.CompileSocket:
    """
      def parse(x: String): Option[Int] =
        try { Some(x.toInt) }
        catch { case _: NumberFormatException => None }

      val op: Option[(String, String)] = Some(("name", "8080"))

      (for ((name, portStr) <- op ;
             port <- parse(portStr)) yield
          List(name, port)
      ) getOrElse (throw new RuntimeException("Malformed"))
    """
      -> msgs("Option.withFilter.flatMap(Option.map).getOrElse"),

    """
      case class Interval(b: Int, c: Int)
      val col = List((1, Interval(2, 3)), (10, Interval(20, 30)))
      for ((a, Interval(b, c)) <- col) yield a + b + c
    """
      -> msgs("List.withFilter.map -> List"),

    """
      class Foo {
        val res = (for (i <- 0 to 10 by 2) yield (() => (i * 3))).map(_())
      }
      new Foo().res
    """
      -> msgs("Range.map.map -> IndexedSeq"),

    "def foo = (1 to 10).map(i => () => i * 3).map(_()); foo"
       -> msgs("Range.map.map -> IndexedSeq"),

    """
      case class Foo(i: Int)
      val arr = new Array[Foo](5);
      for (Foo(i) <- arr) yield i
    """
      -> msgs("Array.withFilter.map -> Array"),

    /// Range.takeWhile and .dropWhile return a Range, which doesn't fit nicely with WhileOps.
    "(1 to 10).takeWhile(_ < 5)" -> CompilerMessages(),
    "(1 to 10).dropWhile(_ < 5)" -> CompilerMessages(),

    "(1 to 10).takeWhile(_ < 5).map(_ * 2)"
      -> msgs("Range.takeWhile.map -> IndexedSeq"),

    "(1 to 10).dropWhile(_ < 5).map(_ * 2)"
      -> msgs("Range.dropWhile.map -> IndexedSeq"),

    "(1 to 10).map(_ * 2).toSet.toList"
      -> msgs("Range.map -> Set"),

    "(1 to 10).map(_ * 2).toVector.toList"
      -> msgs("Range.map.toVector -> List"),

    "List(1, 2, 2, 3).toVector.map((_: Int) * 2).toList"
      -> msgs("List.toVector.map -> List"),

    "Array((1, 2), (3, 4), (5, 6)) find (_._1 > 1) map (_._2)"
      -> msgs("Array.find.map -> Option"),

    "(1 to 10).count(_ < 5)"
      -> msgs("Range.count"),

    "(1L to 2L).map(_ + 1)"
      -> msgs("Range.map -> IndexedSeq"),

    "(0 to 2).map(i => (1, i))"
      -> msgs("Range.map -> IndexedSeq"),

    "(0 to 2).map(i => (i, i))"
      -> msgs("Range.map -> IndexedSeq"),

    "(0 to 2).map(i => (i, 1))"
      -> msgs("Range.map -> IndexedSeq"),

    "List(1, 2, 3).flatMap(v => List(v * 2, v * 2 + 1)).count(_ % 2 == 0)"
      -> msgs("List.flatMap(List).count"),

    "List(1, 2, 3).flatMap(v => List(v * 2, v * 2 + 1).map(_ + 1)).count(_ % 2 == 0)"
      -> msgs("List.flatMap(List.map).count"),

    "Array(1, 2, 3, 4).flatMap(v => Array(v, v * 2).find(_ > 2))"
      -> msgs("Array.flatMap(Array.find) -> Array"),

    "(0 to 10 by 2).exists(_ % 2 == 0)" -> msgs("Range.exists"),
    "(0 to 10 by 2).forall(_ % 2 == 0)" -> msgs("Range.forall"),
    "(1 to 10 by 2).exists(_ % 2 == 0)" -> msgs("Range.exists"),
    "(1 to 10 by 2).forall(_ % 2 == 0)" -> msgs("Range.forall"),

    "Array(1, 2, 3, 4).flatMap(v => List(v, v * 2).map(_ + 1)).find(_ > 2)"
      -> msgs("Array.flatMap(List.map).find -> Option"),

    "Option(10).map(_ * 2).getOrElse(10)"
      -> msgs("Option.map.getOrElse"),

    "(None: Option[Int]).getOrElse(10)"
      -> msgs("Option.getOrElse"),

    "Option[Any](null).getOrElse(10)"
      -> msgs("Option.getOrElse"),

    "Option[AnyRef](null).getOrElse(10)"
      -> msgs("Option.getOrElse"),

    "Option(10).filter(_ < 5).isEmpty"
      -> msgs("Option.filter.isEmpty"),

    "Some(10).map(_ * 2).get"
      -> msgs("Some.map.get"),

    "(None: Option[Any]).getOrElse(2)"
      -> msgs("Option.getOrElse"),
    "Option[Any](null).getOrElse(2)"
      -> msgs("Option.getOrElse"),
    "Some(1).getOrElse(2)"
      -> msgs("Some.getOrElse"),
    "(None: Option[Any]).orNull"
      -> msgs("Option.orNull"),
    "Option[Any](null).orNull"
      -> msgs("Option.orNull"),
    "Some[Any](1).orNull"
      -> msgs("Some.orNull"),

    "(None: Option[Int]).isEmpty"
      -> msgs("Option.isEmpty"),
    "Option[Any](null).isEmpty"
      -> msgs("Option.isEmpty"),
    "Some(1).isEmpty"
      -> msgs("Some.isEmpty"),
    "(None: Option[Int]).isDefined"
      -> msgs("Option.isDefined"),
    "Option[Any](null).isDefined"
      -> msgs("Option.isDefined"),
    "Some(1).isDefined"
      -> msgs("Some.isDefined"),
    "(None: Option[Int]).nonEmpty"
      -> msgs("Option.nonEmpty"),
    "Option[Any](null).nonEmpty"
      -> msgs("Option.nonEmpty"),
    "Some(1).nonEmpty"
      -> msgs("Some.nonEmpty"),

    "(Array[Int]()).isEmpty"
      -> msgs("Array.isEmpty"),
    "List[Int]().isEmpty"
      -> msgs("List.isEmpty"),
    "(0 until 0).isEmpty"
      -> msgs("Range.isEmpty"),
    "(Array[Int]()).nonEmpty"
      -> msgs("Array.nonEmpty"),
    "List[Int]().nonEmpty"
      -> msgs("List.nonEmpty"),
    "(0 until 0).nonEmpty"
      -> msgs("Range.nonEmpty"),

    "for (o <- Some(Some(10)); v <- o) yield v"
      -> msgs("Some.flatMap(Option.map) -> Option"),

    "Some(Some((1, 2))).flatMap(o => o.map(p => (p._1, p._2)))"
      -> msgs("Some.flatMap(Option.map) -> Option"),

    "for (o <- Some(Some((1, 2))); (a, b) <- o) yield a + b"
      -> msgs("Some.flatMap(Option.withFilter.map) -> Option"),

    "List(1, 2, 3).map(_ * 2)"
      -> msgs("List.map -> List"),

    "Array(1, 2, 3).flatMap { case 1 => Some(10) case v => Some(v * 100) }"
      -> msgs("Array.flatMap -> Array"),

    "val n = 3; (1 to n) map (_ * 2)"
      -> msgs("Range.map -> IndexedSeq"),

    "val n = 3; (1 to n).toList map (_ * 2)"
      -> msgs("Range.toList.map -> List"),

    "val n = 3; (1 to n).toArray map (_ * 2)"
      -> msgs("Range.toArray.map -> Array"),

    "Option(1).map(_ * 2).filter(_ < 3)"
      -> msgs("Option.map.filter -> Option"),

    """Option("a").flatMap(v => Option(v).filter(_ != null))"""
      -> msgs("Option.flatMap(Option.filter) -> Option"),

    "(None: Option[String]).map(_ * 2)"
      -> msgs("Option.map -> Option"),

    "Some(1).map(_ * 2).filter(_ < 3)"
      -> msgs("Some.map.filter -> Option"),

    "Seq(0, 1, 2, 3).map(_ * 2).filter(_ < 3)"
      -> msgs("Seq.map.filter -> Seq"),

    "List(0, 1, 2, 3).map(_ * 2).filter(_ < 3)"
      -> msgs("List.map.filter -> List"),

    "Array(1, 2, 3).map(_ * 2).filter(_ < 3)"
      -> msgs("Array.map.filter -> Array"),

    "Array(1, 2, 3).map(_ * 2).filter(_ < 3).toSet"
      -> msgs("Array.map.filter -> Set"),

    "Array(1, 2, 3).map(_ * 2).filter(_ < 3).toList"
      -> msgs("Array.map.filter -> List"),

    "Array(1, 2, 3).map(_ * 2).filter(_ < 3).toVector"
      -> msgs("Array.map.filter -> Vector"),

    "{ val list = List(1, 2, 3); list.map(_ * 2).filter(_ < 3) }"
      -> msgs("List.map.filter -> List"),

    "(Nil: scala.collection.immutable.List[Int]).map(_ * 2).filter(_ < 3).toArray"
      -> msgs("List.map.filter -> Array"),

    "(1 :: 2 :: 3 :: Nil).map(_ * 2).filter(_ < 3).toArray"
      -> msgs("List.map.filter -> Array"),

    "(1 to 3).map(_ * 2).filter(_ < 3).toArray"
      -> msgs("Range.map.filter -> Array"),

    "List(1, 2).reduceLeft((a: Int, b: Int) => a * a + b)"
      -> msgs("List.reduceLeft"),

    "List[Int]().reduceLeft(_ + _)"
      -> msgs("List.reduceLeft"),

    "Array[Int]().reduceLeft(_ + _)"
      -> msgs("Array.reduceLeft"),

    "(1 to 3).reduceLeft(_ + _)"
      -> msgs("Range.reduceLeft"),

    "List(3, 1, 2).reduceLeft(_ min _)"
      -> msgs("List.reduceLeft"),

    """(
      List[Int]().sum, List[Long]().sum, List[Short]().sum, List[Byte]().sum,
      List[Double]().sum, List[Float]().sum
    )"""
      -> msgs("List.sum", "List.sum", "List.sum", "List.sum", "List.sum", "List.sum"),

    """(
      List[Int]().product, List[Long]().product, List[Short]().product, List[Byte]().product,
      List[Double]().product, List[Float]().product
    )"""
      -> msgs("List.product", "List.product", "List.product", "List.product", "List.product", "List.product"),

    "List[Int]().product"
      -> msgs("List.product"),

    "(1 to 3).map(_ + 1).sum"
      -> msgs("Range.map.sum"),

    "Array(1, 2, 3).map(_ * 10).sum"
      -> msgs("Array.map.sum"),

    "Array(1, 2, 3).map(_ * 10).product"
      -> msgs("Array.map.product"),

    "val n = 10; for (v <- 0 to n) yield v"
      -> msgs("Range.map -> IndexedSeq"),

    "Array(1, 2, 3).map(_ * 2).filterNot(_ < 3)"
      -> msgs("Array.map.filterNot -> Array"),

    "(2 to 10).map(_ * 2).filter(_ < 3)"
      -> msgs("Range.map.filter -> IndexedSeq"),

    "(2 until 10 by 2).map(_ * 2)"
      -> msgs("Range.map -> IndexedSeq"),

    "(20 to 7 by -3).map(_ * 2).filter(_ < 3)"
      -> msgs("Range.map.filter -> IndexedSeq"),

    """List(1, 2, 3).mkString("pre", ";", "post")"""
      -> msgs("List.mkString"),

    """List(1, 2, 3).mkString("+")"""
      -> msgs("List.mkString"),

    """List(1, 2, 3).map(_ + 1).mkString("+")"""
      -> msgs("List.map.mkString"),

    """List(1, 2, 3).mkString"""
      -> msgs("List.mkString"),

    """
      val values = List("a", "b");
      (
        for (lhs <- values; rhs <- values) yield {
          lhs + rhs
        }
      ).mkString(",")
    """
      -> msgs("List.flatMap(List.map).mkString"),

    "Array(1, 2, 3).map(_ * 2).map(_ < 3)"
      -> msgs("Array.map.map -> Array"),

    "(10 to 20).map(i => () => i).map(_())"
      -> msgs("Range.map.map -> IndexedSeq"),

    "(10 to 20).map(_ + 1).map(i => () => i).map(_())"
      -> msgs("Range.map.map.map -> IndexedSeq"),

    "(10 to 20).map(_ * 10).map(i => () => i).reverse.map(_())"
      -> msgs("Range.map.map -> IndexedSeq"),

    "for (p <- (20 until 0 by -2).zipWithIndex) yield p.toString"
      -> msgs("Range.zipWithIndex.map -> IndexedSeq"),

    "for ((v, i) <- (20 until 0 by -2).zipWithIndex) yield (v + i)"
      -> msgs("Range.zipWithIndex.withFilter.map -> IndexedSeq"),

    "Array((1, 2)).map({ case (x, y) => x + y })"
      -> msgs("Array.map -> Array"),

    """Array((1, 2), (3, 4))
        .map(_ match { case p @ (i, j) => (i * 10, j / 10.0) })
        .map({ case (k, l) => k + l })"""
      -> msgs("Array.map.map -> Array"),

    "Array(1, 3, 4).take(2)"
      -> msgs("Array.take -> Array"),

    "List(1, 3, 4).take(2)"
      -> msgs("List.take -> List"),

    """
      // Range.drop returns a Range.
      // Option.drop returns a List.
      // Both these cases are not handled by Streams yet, causing
      // the relevant streams to be discarded by Strategies.hasKnownLimitationOrBug.
      def f(i: Int) = true;
      (
        (0 to 2).takeWhile(f),
          Option(1).takeWhile(f), Some(1).takeWhile(f), None.takeWhile(f),
        (0 to 2).dropWhile(f),
          Option(1).dropWhile(f), Some(1).dropWhile(f), None.dropWhile(f),
        (0 to 2).take(2), Option(1).take(2), Some(1).take(2), None.take(2),
        (0 to 2).drop(2), Option(1).drop(2), Some(1).drop(2), None.drop(2)
      )
    """
      -> msgs(),

    """
      // This one throws in LambdaLift because symbol foo is not found.
      // Because of this, any Try sub-tree in a stream causes the stream to
      // be discarded by Strategies.hasKnownLimitationOrBug.
      // See https://github.com/ochafik/Scalaxy/issues/20.
      val msg = {
        try {
          val foo = 10
          Some(foo)
        } catch {
          case ex: Throwable => None
        }
      } get;
      msg
    """
      -> msgs(),

    """
      // By-value params in method called by sub-trees seem to cause symbol
      // ownership issues. Here it's because of x, which is not found anymore.
      // See https://github.com/ochafik/Scalaxy/issues/21.
      def wrap[T](body: => T): Option[T] = Option(body)
      wrap({ val x = 10; Option(x) }) getOrElse 0
    """
      -> msgs(),

    """
      val col: List[Int] = (0 to 2).toList;
      col.filter(v => (v % 2) == 0).map(_ * 2)
    """
      -> msgs("Range.toList -> List", "List.filter.map -> List"),

    """val n = 10;
      for (i <- 0 to n;
           j <- i to 1 by -1;
           if i % 2 == 1)
        yield { i + j }
    """
      -> msgs("Range.flatMap(Range.withFilter.map) -> IndexedSeq"),

    """val n = 10;
      for (i <- 0 to n;
           j <- i to 0 by -1)
        yield { i + j }
    """
      -> msgs("Range.flatMap(Range.map) -> IndexedSeq"),

    """val n = 20;
      for (i <- 0 to n;
           j <- i to n;
           k <- j to n;
           l <- k to n;
           sum = i + j + k + l;
           if sum % 3 == 0;
           m <- l to n)
        yield { sum * m }
    """
      -> msgs("Range.flatMap(Range.flatMap(Range.flatMap(Range.map.withFilter.flatMap(Range.map)))) -> IndexedSeq"),

    """val n = 20;
      for (i <- 0 to n;
           ii = i * i;
           j <- i to n;
           jj = j * j;
           if (ii - jj) % 2 == 0;
           k <- (i + j) to n)
        yield { (ii, jj, k) }
    """
      -> msgs("Range.map.flatMap(Range.map.withFilter.flatMap(Range.map)) -> IndexedSeq"),

    """
      val n = 5
      for (i <- 0 until n; v <- (i to 0 by -1).toArray; j <- 0 until v) yield {
        (i, v, j)
      }
    """
      -> msgs("Range.flatMap(Range.toArray.flatMap(Range.map)) -> IndexedSeq"),

    """
      val start = 10
      val end = 20
      (for (i <- start to end by 2) yield
          (() => (i * 2))
      ).map(_())
    """
      -> msgs("Range.map.map -> IndexedSeq"),

    """
      var tot = 0;
      val n, m, o, p = 3;
      for (i <- 0 until n)
        for (j <- 0 until m)
          for (k <- 0 until o)
            for (l <- 0 until p)
              tot += (i * 1 + j * 10 + k * 100 + l * 1000) / 10;
      tot
    """
      -> msgs((1 to 4).map(_ => "Range.foreach"):_*),

    """
      def foo[T](v: List[(Int, T)]) = v.map(_._2).filter(_ != null);
      foo(List((1, "a")))
    """
      -> msgs("List.map.filter -> List"),

    """
      val o = Option(10)
      def foo(v: Option[Option[Int]]) = v.flatMap(a => {
        val m = a
        m
      })
    """
      -> msgs("Option.flatMap -> Option"),

    "List(Some(List(1)), None).flatMap(_.getOrElse(List(-1)))"
      -> msgs("List.flatMap -> List", "Option.getOrElse"),
      // TODO: -> msgs("List.flatMap(Option.getOrElse) -> List"),

    "Option(Some(2)).flatten"
      -> msgs("Option.flatten -> Option", "Option.foreach"),

    "Option(Option(1)).flatten"
      -> msgs("Option.flatten -> Option", "Option.foreach"),

    "Seq(Option(None).flatten)"
      -> msgs(),

    "List(List(1, 2), List(3, 4)).flatten"
      -> msgs("List.flatten -> List", "List.foreach"),

    "List(List(1, 2), Seq(3, 4), Set(5, 6)).flatten"
      -> msgs("List.flatten -> List"),

    "List(Option(1), None, Some(2)).flatten"
      -> msgs("List.flatten -> List", "Option.foreach"),

    "var tot = 0; for (i <- 0 until 10; x = new AnyRef) { tot += i }; tot"
      -> msgs("Range.map.foreach"),

    "var tot = 0; for (i <- 0 until 10; x = 8) { tot += i }; tot"
      -> msgs("Range.map.foreach")

  ).map({
    case (src, msgs) =>
      IntegrationTest(src.replaceAll(raw"(?m)\s+", " ").trim, src, msgs)
  })
}
