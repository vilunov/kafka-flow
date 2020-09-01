package com.evolutiongaming.kafka.flow.journal

import cats.Monad
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import cats.mtl.MonadState
import com.evolutiongaming.kafka.flow.kafka.ToOffset
import com.evolutiongaming.skafka.Offset
import com.evolutiongaming.sstream.Stream
import com.olegpy.meow.effects._
import scala.collection.immutable.SortedMap

private[journal] trait JournalDatabase[F[_], K, E] {

  /** Adds or replaces the event in a database */
  def persist(key: K, event: E): F[Unit]

  /** Restores journal for the key, if any */
  def get(key: K): Stream[F, E]

  /** Deletes journal for they key, if any */
  def delete(key: K): F[Unit]

}
private[journal] object JournalDatabase {

  /** Creates in-memory database implementation.
    *
    * The data will survive destruction of specific `Journals` instance,
    * but will not survive destruction of specific `JournalDatabase` instance.
    */
  def memory[F[_]: Sync, K, E](implicit E: ToOffset[E]): F[JournalDatabase[F, K, E]] =
    Ref.of[F, Map[K, SortedMap[Offset, E]]](Map.empty) map { storage =>
      memory(storage.stateInstance)
    }

  /** Creates in-memory database implementation.
    *
    * The data will survive destruction of specific `Journals` instance,
    * but will not survive destruction of specific `JournalDatabase` instance.
    */
  def memory[F[_]: Monad, K, E](
    storage: MonadState[F, Map[K, SortedMap[Offset, E]]]
  )(implicit E: ToOffset[E]): JournalDatabase[F, K, E] =
    new JournalDatabase[F, K, E] {

      def persist(key: K, event: E) = storage modify { storage =>
        val existingEvents = storage.getOrElse(key, SortedMap.empty[Offset, E])
        val updatedEvents = existingEvents + (E.offset(event) -> event)
        storage + (key -> updatedEvents)
      }

      def get(key: K) = Stream.lift(storage.get) flatMap { storage =>
        val events = storage get key map (_.values.toList) getOrElse Nil
        Stream.from(events)
      }

      def delete(key: K) = storage modify (_ - key)

    }

}