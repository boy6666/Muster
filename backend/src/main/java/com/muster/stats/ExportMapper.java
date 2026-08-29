package com.muster.stats;

import com.muster.roster.dto.ArchiveDetailRow;
import com.muster.roster.dto.JoinedRow;
import com.muster.roster.dto.MissingRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExportMapper {

    @Select("""
            SELECT p.name, p.phone, p.department, t.name AS teamName, t.submitted_at AS submittedAt
            FROM team_member tm
            JOIN team t ON tm.team_id = t.id
            JOIN person p ON tm.person_id = p.id
            WHERE t.activity_id = #{activityId}
            ORDER BY t.id, tm.id
            """)
    List<JoinedRow> selectJoined(Long activityId);

    @Select("""
            SELECT p.name, p.phone, p.department
            FROM person p
            WHERE p.activity_id = #{activityId}
              AND NOT EXISTS (SELECT 1 FROM team_member tm WHERE tm.person_id = p.id)
            ORDER BY p.id
            """)
    List<MissingRow> selectMissing(Long activityId);

    @Select("""
            SELECT t.name AS teamName, p.name AS memberName, p.phone, p.department,
                   t.status AS teamStatus, t.reject_reason AS rejectReason
            FROM team t
            JOIN team_member tm ON tm.team_id = t.id
            JOIN person p ON tm.person_id = p.id
            WHERE t.activity_id = #{activityId}
            ORDER BY t.id, tm.id
            """)
    List<ArchiveDetailRow> selectArchiveDetail(Long activityId);
}
