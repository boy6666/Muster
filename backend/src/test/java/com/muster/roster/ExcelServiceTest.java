package com.muster.roster;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExcelServiceTest {

    private final ExcelService excelService = new ExcelService();

    private List<Map<Integer, String>> readAll(byte[] bytes) {
        List<Map<Integer, String>> rows = new ArrayList<>();
        EasyExcel.read(new ByteArrayInputStream(bytes), new ReadListener<Map<Integer, String>>() {
            @Override
            public void invoke(Map<Integer, String> row, AnalysisContext context) {
                rows.add(row);
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
            }
        }).sheet().doRead();
        return rows;
    }

    @Test
    void templateContainsHeaderAndExampleRow() {
        var read = readAll(excelService.writeTemplate());
        assertThat(read).hasSize(1);
        assertThat(read.get(0).get(0)).isEqualTo("1001");
        assertThat(read.get(0).get(1)).isEqualTo("张三");
        assertThat(read.get(0).get(2)).isEqualTo("13812345678");
        assertThat(read.get(0).get(3)).isEqualTo("计算机系");
    }

    @Test
    void readPersonsMapsFourColumnsWithRowNoFromTwo() {
        var head = List.of(List.of("员工编号"), List.of("姓名"), List.of("手机号"), List.of("部门"));
        var data = List.of(
                List.<Object>of("E001", "张三", "13812345678", "计算机系"),
                List.<Object>of("E002", "李四", "bad", "外语系"));
        var out = new ByteArrayOutputStream();
        EasyExcel.write(out).head(head).sheet("s").doWrite(data);

        var read = excelService.readPersons(new ByteArrayInputStream(out.toByteArray()));
        assertThat(read).hasSize(2);
        assertThat(read.get(0).rowNo()).isEqualTo(2);
        assertThat(read.get(0).employeeId()).isEqualTo("E001");
        assertThat(read.get(0).name()).isEqualTo("张三");
        assertThat(read.get(0).phone()).isEqualTo("13812345678");
        assertThat(read.get(0).department()).isEqualTo("计算机系");
        assertThat(read.get(1).rowNo()).isEqualTo(3);
        assertThat(read.get(1).employeeId()).isEqualTo("E002");
        assertThat(read.get(1).phone()).isEqualTo("bad");
    }
}
