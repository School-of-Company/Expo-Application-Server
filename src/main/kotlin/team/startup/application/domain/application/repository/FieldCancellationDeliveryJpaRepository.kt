package team.startup.application.domain.application.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.entity.FieldCancellationDelivery
import team.startup.application.domain.application.service.impl.FieldCancellationTarget
import java.util.UUID

@Transactional(readOnly = true)
interface FieldCancellationDeliveryJpaRepository : JpaRepository<FieldCancellationDelivery, UUID> {
    @Query(
        value = "SELECT * FROM tb_field_cancellation_delivery WHERE next_attempt_at <= now() ORDER BY next_attempt_at LIMIT 100",
        nativeQuery = true,
    )
    fun pending(): List<FieldCancellationDelivery>

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value =
            "INSERT INTO tb_field_cancellation_delivery " +
                "(registration_id,expo_id,participant_id,visit_date,generation,operation_id,actor_participant_id,action) " +
                "VALUES (:#{#target.registrationId},:#{#target.expoId},:#{#target.participantId}," +
                ":#{#target.visitDate},:#{#target.generation},:operationId,:actorParticipantId,'ACQUIRING')",
        nativeQuery = true,
    )
    fun insert(
        @Param("target") target: FieldCancellationTarget,
        @Param("operationId") operationId: UUID,
        @Param("actorParticipantId") actorParticipantId: Long,
    ): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FieldCancellationDelivery SET action = :action WHERE operationId = :operationId AND action = 'ACQUIRING'")
    fun decide(
        @Param("operationId") operationId: UUID,
        @Param("action") action: String,
    ): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM FieldCancellationDelivery WHERE operationId = :operationId")
    fun deleteOperation(
        @Param("operationId") operationId: UUID,
    ): Int

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = "UPDATE tb_field_cancellation_delivery SET next_attempt_at = now() + interval '1 minute' WHERE operation_id = :operationId",
        nativeQuery = true,
    )
    fun defer(
        @Param("operationId") operationId: UUID,
    ): Int
}
