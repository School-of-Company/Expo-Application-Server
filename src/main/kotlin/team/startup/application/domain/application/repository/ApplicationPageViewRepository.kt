package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.presentation.dto.ApplicationPageViewDay
import java.time.LocalDate
import java.util.UUID

@Repository
class ApplicationPageViewRepository(
    private val jdbc: JdbcTemplate,
) {
    fun record(
        expoId: UUID,
        date: LocalDate,
        visitorHash: ByteArray,
    ) {
        val newVisitor =
            jdbc.update(
                "INSERT INTO tb_application_page_visitor (expo_id, view_date, visitor_hash) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                expoId,
                date,
                visitorHash,
            )
        jdbc.update(
            "INSERT INTO tb_application_page_view_daily (expo_id, view_date, page_views, unique_visitors) VALUES (?, ?, 1, ?) " +
                "ON CONFLICT (expo_id, view_date) DO UPDATE SET page_views = tb_application_page_view_daily.page_views + 1, " +
                "unique_visitors = tb_application_page_view_daily.unique_visitors + EXCLUDED.unique_visitors",
            expoId,
            date,
            newVisitor,
        )
    }

    fun daily(expoId: UUID): List<ApplicationPageViewDay> =
        jdbc.query(
            "SELECT view_date, page_views, unique_visitors FROM tb_application_page_view_daily WHERE expo_id = ? ORDER BY view_date",
            { rs, _ -> ApplicationPageViewDay(rs.getObject(1, LocalDate::class.java), rs.getLong(2), rs.getLong(3)) },
            expoId,
        )

    fun purgeVisitorsBefore(date: LocalDate): Int = jdbc.update("DELETE FROM tb_application_page_visitor WHERE view_date < ?", date)
}
