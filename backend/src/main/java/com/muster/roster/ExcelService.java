package com.muster.roster;

import com.alibaba.excel.EasyExcel;
import com.muster.roster.dto.JoinedRow;
import com.muster.roster.dto.MissingRow;
import com.muster.roster.dto.PersonRow;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ExcelService {

    public List<PersonRow> readPersons(InputStream inputStream) {
        List<Map<Integer, String>> rows = EasyExcel.read(inputStream).sheet().doReadSync();
        List<PersonRow> result = new ArrayList<>();
        int rowNo = 2; // 表头占第 1 行，数据从第 2 行计
        for (Map<Integer, String> row : rows) {
            result.add(new PersonRow(rowNo++, str(row.get(0)), str(row.get(1)), str(row.get(2)), str(row.get(3))));
        }
        return result;
    }

    public byte[] writeTemplate() {
        List<List<Object>> example = List.of(List.of((Object) "1001", "张三", "13812345678", "计算机系"));
        return write(head("员工编号", "姓名", "手机号", "部门"), example, "花名册模板");
    }

    public byte[] writeJoined(List<JoinedRow> rows) {
        List<List<Object>> data = new ArrayList<>();
        for (JoinedRow r : rows) {
            data.add(List.of(s(r.name()), s(r.phone()), s(r.department()), s(r.teamName()), s(r.submittedAt())));
        }
        return write(head("姓名", "手机号", "部门", "组名", "提交时间"), data, "已参加");
    }

    public byte[] writeMissing(List<MissingRow> rows) {
        List<List<Object>> data = new ArrayList<>();
        for (MissingRow r : rows) {
            data.add(List.of(s(r.name()), s(r.phone()), s(r.department())));
        }
        return write(head("姓名", "手机号", "部门"), data, "未参加");
    }

    public byte[] writeArchive(List<JoinedRow> joined, List<MissingRow> missing,
                               List<com.muster.roster.dto.ArchiveDetailRow> details) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            com.alibaba.excel.ExcelWriter writer = EasyExcel.write(out).build();
            com.alibaba.excel.write.metadata.WriteSheet joinedSheet = EasyExcel.writerSheet(0, "已参加")
                    .head(head("姓名", "手机号", "部门", "组名", "提交时间")).build();
            List<List<Object>> joinedData = new ArrayList<>();
            for (JoinedRow r : joined) {
                joinedData.add(List.of(s(r.name()), s(r.phone()), s(r.department()),
                        s(r.teamName()), s(r.submittedAt())));
            }
            writer.write(joinedData, joinedSheet);

            com.alibaba.excel.write.metadata.WriteSheet missingSheet = EasyExcel.writerSheet(1, "未参加")
                    .head(head("姓名", "手机号", "部门")).build();
            List<List<Object>> missingData = new ArrayList<>();
            for (MissingRow r : missing) {
                missingData.add(List.of(s(r.name()), s(r.phone()), s(r.department())));
            }
            writer.write(missingData, missingSheet);

            com.alibaba.excel.write.metadata.WriteSheet detailSheet = EasyExcel.writerSheet(2, "分组明细")
                    .head(head("组名", "组员姓名", "手机号", "部门", "组状态", "驳回理由")).build();
            List<List<Object>> detailData = new ArrayList<>();
            for (com.muster.roster.dto.ArchiveDetailRow r : details) {
                detailData.add(List.of(s(r.teamName()), s(r.memberName()), s(r.phone()),
                        s(r.department()), s(r.teamStatus()), s(r.rejectReason())));
            }
            writer.write(detailData, detailSheet);

            writer.finish();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("生成归档包失败", e);
        }
    }

    private byte[] write(List<List<String>> head, List<List<Object>> data, String sheetName) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            EasyExcel.write(out).head(head).sheet(sheetName).doWrite(data);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("生成 Excel 失败", e);
        }
    }

    private List<List<String>> head(String... columns) {
        List<List<String>> head = new ArrayList<>();
        for (String column : columns) {
            head.add(List.of(column));
        }
        return head;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String s(String value) {
        return value == null ? "" : value;
    }
}
