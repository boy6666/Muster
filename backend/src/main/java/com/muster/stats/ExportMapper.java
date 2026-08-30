package com.muster.stats;

import com.muster.roster.dto.ArchiveDetailRow;
import com.muster.roster.dto.JoinedRow;
import com.muster.roster.dto.MissingRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExportMapper {

    /** 已参加 = 仅通过审核（CONFIRMED）的组成员。 */
    @Select("""
            SELECT p.employee_id AS employeeId, p.name, p.phone, p.department,
                   t.name AS teamName, IFNULL(tm.person_id = t.leader_person_id, 0) AS isLeader
            FROM team_member tm
            JOIN team t ON tm.team_id = t.id
            JOIN person p ON tm.person_id = p.id
            WHERE t.activity_id = #{activityId} AND t.status = 'CONFIRMED'
            ORDER BY t.id, tm.id
            """)
    List<JoinedRow> selectJoined(Long activityId);

    /** 未参加 = 不在任何通过组里的人（待审核/草稿/未报名都算）。 */
    @Select("""
            SELECT p.employee_id AS employeeId, p.name, p.phone, p.department
            FROM person p
            WHERE p.activity_id = #{activityId}
              AND NOT EXISTS (SELECT 1 FROM team_member tm JOIN team t ON tm.team_id = t.id
                              WHERE tm.person_id = p.id AND t.status = 'CONFIRMED')
            ORDER BY p.id
            """)
    List<MissingRow> selectMissing(Long activityId);

    /** 归档分组明细：全部组（含草稿），无成员的组留空行。 */
    @Select("""
            SELECT t.name AS teamName, p.employee_id AS employeeId, p.name AS memberName,
                   p.phone, p.department, t.status AS teamStatus, t.reject_reason AS rejectReason
            FROM team t
            LEFT JOIN team_member tm ON tm.team_id = t.id
            LEFT JOIN person p ON tm.person_id = p.id
            WHERE t.activity_id = #{activityId}
            ORDER BY t.id, tm.id
            """)
    List<ArchiveDetailRow> selectArchiveDetail(Long activityId);
}
