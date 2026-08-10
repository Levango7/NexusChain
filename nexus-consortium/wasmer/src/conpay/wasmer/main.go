package main

import (
	"encoding/json"
	"fmt"
	"io"
	"os"

	wasm "github.com/wasmerio/go-ext-wasm/wasmer"
)

type WasmFormat struct {
	Bytes  []byte      `json:"bytes"`
	Params []WasmParam `json:"params"`
}

type WasmParam struct {
	Value         interface{}   `json:"value"`
	WasmParamType WasmParamType `json:"type"`
}

type WasmParamType string

const (
	WasmParams_I32 WasmParamType = "I32"
	WasmParams_I64               = "I64"
	WasmParams_F32               = "F32"
	WasmParams_F64               = "F64"
)

func main() {
	data, err := ReadFrom(os.Stdin)
	if err != nil {
		panic(err)
	}

	var wasmFormat WasmFormat
	errUnmarshal := json.Unmarshal(data, &wasmFormat)
	if errUnmarshal != nil {
		panic(errUnmarshal)
	}
	instance, _ := wasm.NewInstance(wasmFormat.Bytes)
	defer instance.Close()
	// Gets the `sum` exported function from the WebAssembly instance.
	sum := instance.Exports["sum"]
	// Calls that exported function with Go standard values. The WebAssembly
	// types are inferred and values are casted automatically.
	values := convertWasmValues(wasmFormat.Params...)
	result, _ := sum(values...)
	fmt.Println(result) // 42!
}

func convertWasmValues(params ...WasmParam) []interface{} {
	var values = make([]interface{}, len(params))
	for idx, param := range params {
		switch param.WasmParamType {
		case WasmParams_I32:
			values[idx] = wasm.I32(int32(param.Value.(float64)))
		case WasmParams_I64:
			values[idx] = wasm.I64(int64(param.Value.(float64)))
		case WasmParams_F32:
			values[idx] = wasm.F32(float32(param.Value.(float64)))
		case WasmParams_F64:
			values[idx] = wasm.F64(param.Value.(float64))
		default:
			values[idx] = param.Value
		}
	}
	return values
}

func ReadFrom(reader io.Reader) ([]byte, error) {
	p := make([]byte, 1024)
	n, err := reader.Read(p)
	if n > 0 {
		return p[:n], nil
	}
	return p, err
}
