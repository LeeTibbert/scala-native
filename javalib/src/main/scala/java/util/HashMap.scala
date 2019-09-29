/*
 * Scala.js (https://www.scala-js.org/)
 *
 * Copyright EPFL.
 *
 * Licensed under Apache License 2.0
 * (https://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

/// SN porting notes:
///   Ported, with thanks & gratitude, from Scala-js original dated 2019-08-14
///    commit: https://github.com/scala-js/scala-js/commit/
///               a5c1d2095df42166e75974a39232898e982e5836

package java.util

import scala.annotation.tailrec

import java.{util => ju}

import ScalaOps._

// SN Port: Java 8 specifies loadFactor as Float. Scala.js used Double.

class HashMap[K, V](initialCapacity: Int, loadFactor: Float)
    extends AbstractMap[K, V]
    with Serializable
    with Cloneable {
  self =>

  import HashMap._

  if (initialCapacity < 0)
    throw new IllegalArgumentException("initialCapacity < 0")
  if (loadFactor <= 0.0)
    throw new IllegalArgumentException("loadFactor <= 0.0")

  def this() =
    this(HashMap.DEFAULT_INITIAL_CAPACITY, HashMap.DEFAULT_LOAD_FACTOR)

  def this(initialCapacity: Int) =
    this(initialCapacity, HashMap.DEFAULT_LOAD_FACTOR)

  def this(m: Map[_ <: K, _ <: V]) = {
    this(m.size())
    putAll(m)
  }

  /** The actual hash table.
   *
   *  In each bucket, nodes are sorted by increasing value of `hash`.
   *
   *  Deviation from the JavaDoc: we do not use `initialCapacity` as is for the
   *  number of buckets. Instead we round it up to the next power of 2. This
   *  allows some algorithms to be more efficient, notably `index()` and
   *  `growTable()`. Since the number of buckets is not observable from the
   *  outside, this deviation does not change any semantics.
   */
  private[this] var table = new Array[Node[K, V]](tableSizeFor(initialCapacity))

  /** The next size value at which to resize (capacity * load factor). */
  private[this] var threshold: Int = newThreshold(table.length)

  private[this] var contentSize: Int = 0

  /* Internal API for LinkedHashMap: these methods are overridden in
   * LinkedHashMap to implement its insertion- or access-order.
   */

  private[util] def newNode(key: K,
                            hash: Int,
                            value: V,
                            previous: Node[K, V],
                            next: Node[K, V]): Node[K, V] = {
    new Node(key, hash, value, previous, next)
  }

  private[util] def nodeWasAccessed(node: Node[K, V]): Unit = ()

  private[util] def nodeWasAdded(node: Node[K, V]): Unit = ()

  private[util] def nodeWasRemoved(node: Node[K, V]): Unit = ()

  // Public API

  override def size(): Int =
    contentSize

  override def isEmpty(): Boolean =
    contentSize == 0

  override def get(key: Any): V = {
    val node = findNode(key)
    if (node eq null) {
      null.asInstanceOf[V]
    } else {
      nodeWasAccessed(node)
      node.value
    }
  }

  override def containsKey(key: Any): Boolean =
    findNode(key) ne null

  override def put(key: K, value: V): V =
    put0(key, value)

  override def putAll(m: Map[_ <: K, _ <: V]): Unit = {
    m match {
      case m: ju.HashMap[_, _] =>
        val iter = m.nodeIterator()
        while (iter.hasNext()) {
          val next = iter.next()
          put0(next.key, next.value, next.hash)
        }
      case _ =>
        super.putAll(m)
    }
  }

  override def remove(key: Any): V = {
    val node = remove0(key)
    if (node eq null) null.asInstanceOf[V]
    else node.value
  }

  override def clear(): Unit = {
    ju.Arrays.fill(table.asInstanceOf[Array[AnyRef]], null)
    contentSize = 0
  }

  override def containsValue(value: Any): Boolean =
    valueIterator().scalaOps.exists(Objects.equals(value, _))

  override def keySet(): ju.Set[K] =
    new KeySet

  override def values(): ju.Collection[V] =
    new Values

  def entrySet(): ju.Set[ju.Map.Entry[K, V]] =
    new EntrySet

  override def clone(): AnyRef =
    new HashMap[K, V](this)

  // Elementary operations

  @inline private def index(hash: Int): Int =
    hash & (table.length - 1)

  @inline
  private def findNode(key: Any): Node[K, V] = {
    val hash = computeHash(key)
    findNode0(key, hash, index(hash))
  }

  @inline
  private def findNodeAndIndexForRemoval(key: Any): (Node[K, V], Int) = {
    val hash = computeHash(key)
    val idx  = index(hash)
    val node = findNode0(key, hash, idx)
    (node, idx)
  }

  private def findNode0(key: Any, hash: Int, idx: Int): Node[K, V] = {
    @inline
    @tailrec
    def loop(node: Node[K, V]): Node[K, V] = {
      if (node eq null) null
      else if (hash == node.hash && Objects.equals(key, node.key)) node
      else if (hash < node.hash) null
      else loop(node.next)
    }
    loop(table(idx))
  }

  // Heavy lifting: modifications

  /** Adds a key-value pair to this map
   *
   *  @param key the key to add
   *  @param value the value to add
   *  @return the old value associated with `key`, or `null` if there was none
   */
  @inline
  private[this] def put0(key: K, value: V): V =
    put0(key, value, computeHash(key))

  /** Adds a key-value pair to this map
   *
   *  @param key the key to add
   *  @param value the value to add
   *  @param hash the **improved** hashcode of `key` (see computeHash)
   *  @return the old value associated with `key`, or `null` if there was none
   */
  private[this] def put0(key: K, value: V, hash: Int): V = {
    if (contentSize + 1 >= threshold)
      growTable()
    val idx = index(hash)
    put0(key, value, hash, idx)
  }

  /** Adds a key-value pair to this map
   *
   *  @param key the key to add
   *  @param value the value to add
   *  @param hash the **improved** hashcode of `key` (see computeHash)
   *  @param idx the index in the `table` corresponding to the `hash`
   *  @return the old value associated with `key`, or `null` if there was none
   */
  private[this] def put0(key: K, value: V, hash: Int, idx: Int): V = {
    // scalastyle:off return
    val newNode = table(idx) match {
      case null =>
        val newNode = this.newNode(key, hash, value, null, null)
        table(idx) = newNode
        newNode
      case first =>
        var prev: Node[K, V] = null
        var n                = first
        while ((n ne null) && n.hash <= hash) {
          if (n.hash == hash && Objects.equals(key, n.key)) {
            nodeWasAccessed(n)
            val old = n.value
            n.value = value
            return old
          }
          prev = n
          n = n.next
        }
        val newNode = this.newNode(key, hash, value, prev, n)
        if (prev eq null)
          table(idx) = newNode
        else
          prev.next = newNode
        if (n ne null)
          n.previous = newNode
        newNode
    }
    contentSize += 1
    nodeWasAdded(newNode)
    null.asInstanceOf[V]
    // scalastyle:on return
  }

  /** Removes a key from this map if it exists.
   *
   *  @param key the key to remove
   *  @return the node that contained `key` if it was present, otherwise null
   */
  private def remove0(key: Any): Node[K, V] = {
    val (node, idx) = findNodeAndIndexForRemoval(key)
    if (node ne null)
      remove0(node, idx)
    node
  }

  private[util] final def removeNode(node: Node[K, V]): Unit =
    remove0(node, index(node.hash))

  private def remove0(node: Node[K, V], idx: Int): Unit = {
    val previous = node.previous
    val next     = node.next
    if (previous eq null)
      table(idx) = next
    else
      previous.next = next
    if (next ne null)
      next.previous = previous
    contentSize -= 1
    nodeWasRemoved(node)
  }

  /** Grow the size of the table (always times 2). */
  private[this] def growTable(): Unit = {
    val oldTable = table
    val oldlen   = oldTable.length
    val newlen   = oldlen * 2
    val newTable = new Array[Node[K, V]](newlen)
    table = newTable
    threshold = newThreshold(newlen)

    /* Split the nodes of each bucket from the old table into the "low" and
     * "high" indices of the new table. Since the new table contains exactly
     * twice as many buckets as the old table, every index `i` from the old
     * table is split into indices `i` and `oldlen + i` in the new table.
     */
    var i = 0
    while (i < oldlen) {
      var lastLow: Node[K, V]  = null
      var lastHigh: Node[K, V] = null
      var node                 = oldTable(i)
      while (node ne null) {
        if ((node.hash & oldlen) == 0) {
          // go to low
          node.previous = lastLow
          if (lastLow eq null)
            newTable(i) = node
          else
            lastLow.next = node
          lastLow = node
        } else {
          // go to high
          node.previous = lastHigh
          if (lastHigh eq null)
            newTable(oldlen + i) = node
          else
            lastHigh.next = node
          lastHigh = node
        }
        node = node.next
      }
      if (lastLow ne null)
        lastLow.next = null
      if (lastHigh ne null)
        lastHigh.next = null
      i += 1
    }
  }

  /** Rounds up `capacity` to a power of 2, with a maximum of 2^30. */
  @inline private[this] def tableSizeFor(capacity: Int): Int =
    Math.min(Integer.highestOneBit(Math.max(capacity - 1, 4)) * 2, 1 << 30)

  @inline private[this] def newThreshold(size: Int): Int =
    (size.toDouble * loadFactor).toInt

  // Iterators

  private[util] def nodeIterator(): ju.Iterator[Node[K, V]] =
    new NodeIterator

  private[util] def keyIterator(): ju.Iterator[K] =
    new KeyIterator

  private[util] def valueIterator(): ju.Iterator[V] =
    new ValueIterator

  // The cast works around the lack of definition-site variance
  private[util] final def entrySetIterator(): ju.Iterator[Map.Entry[K, V]] =
    nodeIterator().asInstanceOf[ju.Iterator[Map.Entry[K, V]]]

  private final class NodeIterator extends AbstractHashMapIterator[Node[K, V]] {
    protected[this] def extract(node: Node[K, V]): Node[K, V] = node
  }

  private final class KeyIterator extends AbstractHashMapIterator[K] {
    protected[this] def extract(node: Node[K, V]): K = node.key
  }

  private final class ValueIterator extends AbstractHashMapIterator[V] {
    protected[this] def extract(node: Node[K, V]): V = node.value
  }

  private abstract class AbstractHashMapIterator[A] extends ju.Iterator[A] {
    private[this] val len                  = table.length
    private[this] var nextIdx: Int         = _ // 0
    private[this] var nextNode: Node[K, V] = _ // null
    private[this] var lastNode: Node[K, V] = _ // null

    protected[this] def extract(node: Node[K, V]): A

    /* Movements of `nextNode` and `nextIdx` are spread over `hasNext()` to
     * simplify initial conditions, and preserving as much performance as
     * possible while guaranteeing that constructing the iterator remains O(1)
     * (the first linear behavior can happen when calling `hasNext()`, not
     * before).
     */

    def hasNext(): Boolean = {
      // scalastyle:off return
      if (nextNode ne null) {
        true
      } else {
        while (nextIdx < len) {
          val node = table(nextIdx)
          nextIdx += 1
          if (node ne null) {
            nextNode = node
            return true
          }
        }
        false
      }
      // scalastyle:on return
    }

    def next(): A = {
      if (!hasNext())
        throw new NoSuchElementException("next on empty iterator")
      val node = nextNode
      lastNode = node
      nextNode = node.next
      extract(node)
    }

    def remove(): Unit = {
      val last = lastNode
      if (last eq null)
        throw new IllegalStateException(
          "next must be called at least once before remove")
      removeNode(last)
      lastNode = null
    }
  }

  // Views

  private final class KeySet extends AbstractSet[K] {
    def iterator(): Iterator[K] =
      keyIterator()

    def size(): Int =
      self.size()

    override def contains(o: Any): Boolean =
      containsKey(o)

    override def remove(o: Any): Boolean =
      self.remove0(o) ne null

    override def clear(): Unit =
      self.clear()
  }

  private final class Values extends AbstractCollection[V] {
    def iterator(): ju.Iterator[V] =
      valueIterator()

    def size(): Int =
      self.size()

    override def contains(o: Any): Boolean =
      containsValue(o)

    override def clear(): Unit =
      self.clear()
  }

  private final class EntrySet extends AbstractSet[Map.Entry[K, V]] {
    def iterator(): Iterator[Map.Entry[K, V]] =
      entrySetIterator()

    def size(): Int =
      self.size()

    override def contains(o: Any): Boolean = o match {
      case o: Map.Entry[_, _] =>
        val node = findNode(o.getKey())
        (node ne null) && Objects.equals(node.getValue(), o.getValue())
      case _ =>
        false
    }

    override def remove(o: Any): Boolean = o match {
      case o: Map.Entry[_, _] =>
        val key         = o.getKey()
        val (node, idx) = findNodeAndIndexForRemoval(key)
        if ((node ne null) && Objects.equals(node.getValue(), o.getValue())) {
          remove0(node, idx)
          true
        } else {
          false
        }
      case _ =>
        false
    }

    override def clear(): Unit =
      self.clear()
  }
}

object HashMap {
  private[util] final val DEFAULT_INITIAL_CAPACITY = 16
  private[util] final val DEFAULT_LOAD_FACTOR      = 0.75f

  /** Computes the improved hash of an original (`any.hashCode()`) hash. */
  @inline private def improveHash(originalHash: Int): Int = {
    /* Improve the hash by xoring the high 16 bits into the low 16 bits just in
     * case entropy is skewed towards the high-value bits. We only use the
     * lowest bits to determine the hash bucket.
     *
     * This function is also its own inverse. That is, for all ints i,
     * improveHash(improveHash(i)) = i
     * this allows us to retrieve the original hash when we need it, and that
     * is why unimproveHash simply forwards to this method.
     */
    originalHash ^ (originalHash >>> 16)
  }

  /** Performs the inverse operation of improveHash.
   *
   *  In this case, it happens to be identical to improveHash.
   */
  @inline private def unimproveHash(improvedHash: Int): Int =
    improveHash(improvedHash)

  /** Computes the improved hash of this key */
  @inline private def computeHash(k: Any): Int =
    if (k == null) 0
    else improveHash(k.hashCode())

  private[util] class Node[K, V](val key: K,
                                 val hash: Int,
                                 var value: V,
                                 var previous: Node[K, V],
                                 var next: Node[K, V])
      extends Map.Entry[K, V] {

    def getKey(): K = key

    def getValue(): V = value

    def setValue(v: V): V = {
      val oldValue = value
      value = v
      oldValue
    }

    override def equals(that: Any): Boolean = that match {
      case that: Map.Entry[_, _] =>
        Objects.equals(getKey(), that.getKey()) &&
          Objects.equals(getValue(), that.getValue())
      case _ =>
        false
    }

    override def hashCode(): Int =
      unimproveHash(hash) ^ Objects.hashCode(value)

    override def toString(): String =
      "" + getKey + "=" + getValue
  }
}
