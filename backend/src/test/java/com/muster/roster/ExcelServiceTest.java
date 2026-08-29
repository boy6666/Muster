package com.muster.roster;

import com.alibaba.excel.EasyExcel;
import com.muster.roster.dto.JoinedRow;
import com.muster.roster.dto.MissingRow;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExcelServiceTest {

    private final ExcelService excelService = new ExcelService();

    @Test
    void templateHasNoDataRows() {
        var bytes = excelService.writeTemplate();
        assertThat(excelService.readPersons(new ByteArrayInputStream(bytes))).isEmpty();
    }

    @Test
    void joinedRoundTripPreservesFirstThreeColumns() {
        var rows = List.of(
                new JoinedRow("张三", "13812345678", "计算机", "组1", "2026-09-01 09:00:00"),
                new JoinedRow("李四", "13987654321", "外语", "组2", "2026-09-01 09:05:00"));
        var read = excelService.readPersons(new ByteArrayInputStream(excelService.writeJoined(rows)));
        assertThat(read).hasSize(2);
        assertThat(read.get(0).rowNo()).isEqualTo(2);
        assertThat(read.get(0).name()).isEqualTo("张三");
        assertThat(read.get(0).phone()).isEqualTo("13812345678");
        assertThat(read.get(0).department()).isEqualTo("计算机");
        assertThat(read.get(1).rowNo()).isEqualTo(3);
    }

    @Test
    void missingRoundTripKeepsThreeColumns() {
        var rows = List.of(new MissingRow("王五", "13600000000", "体育"));
        var read = excelService.readPersons(new ByteArrayInputStream(excelService.writeMissing(rows)));
        assertThat(read).hasSize(1);
        assertThat(read.get(0).name()).isEqualTo("王五");
        assertThat(read.get(0).phone()).isEqualTo("13600000000");
        assertThat(read.get(0).department()).isEqualTo("体育");
    }

    @Test
    void rowNoCountsFromSecondExcelRow() {
        var head = List.of(List.of("姓名"), List.of("手机号"), List.of("部门"));
        var data = List.of(
                List.<Object>of("张三", "13812345678", "计算机"),
                List.<Object>of("李四", "bad", "数学"));
        var out = new ByteArrayOutputStream();
        EasyExcel.write(out).head(head).sheet("s").doWrite(data);

        var read = excelService.readPersons(new ByteArrayInputStream(out.toByteArray()));
        assertThat(read).hasSize(2);
        assertThat(read.get(1).rowNo()).isEqualTo(3);
        assertThat(read.get(1).phone()).isEqualTo("bad");
    }
}
