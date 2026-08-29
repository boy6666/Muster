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
            result.add(new PersonRow(rowNo++, str(row.get(0)), str(row.get(1)), str(row.get(2))));
        }
        return result;
    }

    public byte[] writeTemplate() {
        return write(head("姓名", "手机号", "部门"), List.of(), "花名册模板");
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
