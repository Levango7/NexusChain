package org.nexus.consortium.util.vm.wasm;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;


@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WasmFormat {

    private List<WasmParam> params;

    private byte[] bytes;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WasmParam {

        enum Wasm_Type {I32, I64, F32, F64}

        private Wasm_Type type;

        private Object value;

    }

}
