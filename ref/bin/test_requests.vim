" Test script to capture requests sent by llama.vim to the server
" This script tests how the plugin selects prefix, middle, and suffix
" Usage: vim -u NONE -N -S test_requests.vim

" Source the llama.vim plugin
source ref/llama.vim/autoload/llama.vim

" Initialize llama.vim to set up all script-local variables
call llama#init()

" Configure llama.vim to use our mock server (extend existing config)
let g:llama_config.endpoint = 'http://localhost:8012/infill'
let g:llama_config.n_prefix = 256
let g:llama_config.n_suffix = 64
let g:llama_config.n_predict = 128

" Output file for results
let s:output_file = 'src/test/testData/completion/fim_requests_corpus.json'
let s:results = []

" Helper function to escape strings for JSON
function! s:json_escape(str)
    let l:str = a:str
    let l:str = substitute(l:str, '\\', '\\\\', 'g')
    let l:str = substitute(l:str, '"', '\\"', 'g')
    let l:str = substitute(l:str, "\n", '\\n', 'g')
    let l:str = substitute(l:str, "\r", '\\r', 'g')
    let l:str = substitute(l:str, "\t", '\\t', 'g')
    return l:str
endfunction

" Helper function to build full server response for fim_render testing
function! s:build_response(content)
    return json_encode({
        \ 'content': a:content,
        \ 'timings/prompt_n': 50,
        \ 'timings/prompt_ms': '10.5',
        \ 'timings/prompt_per_second': '4761.9',
        \ 'timings/predicted_n': 20,
        \ 'timings/predicted_ms': '50.2',
        \ 'timings/predicted_per_second': '398.4',
        \ 'tokens_cached': 100,
        \ 'timings/truncated': v:false
        \ })
endfunction

" Test a specific position and make a request to the server
function! s:test_position(file_path, line_num, col_num, test_id)
    " Open the file
    execute 'edit ' . a:file_path

    " Move cursor to the position
    call cursor(a:line_num, a:col_num + 1)  " Vim columns are 1-indexed

    " Get the local context
    let l:ctx = llama#fim_ctx_local(a:col_num, a:line_num, [])

    let l:prefix = l:ctx['prefix']
    let l:middle = l:ctx['middle']
    let l:suffix = l:ctx['suffix']
    let l:indent = l:ctx['indent']

    " Build the request JSON
    let l:request = {
        \ 'id_slot': 0,
        \ 'input_prefix': l:prefix,
        \ 'input_suffix': l:suffix,
        \ 'prompt': l:middle,
        \ 'n_predict': 128,
        \ 'stop': [],
        \ 'n_indent': l:indent,
        \ 'top_k': 40,
        \ 'top_p': 0.90,
        \ 'stream': v:false,
        \ 'samplers': ['top_k', 'top_p', 'infill'],
        \ 'cache_prompt': v:true,
        \ 't_max_prompt_ms': 500,
        \ 't_max_predict_ms': 1000,
        \ 'extra_input': [a:test_id]
        \ }

    let l:request_json = json_encode(l:request)

    " Make the request using curl
    let l:cmd = 'curl -s -X POST "http://localhost:8012/infill" -H "Content-Type: application/json" -d ''' . escape(l:request_json, "'") . ''''

    " Execute curl and capture response
    let l:response = system(l:cmd)

    " Parse the response
    let l:response_obj = json_decode(l:response)
    let l:result = get(l:response_obj, 'result', get(l:response_obj, 'content', ''))

    " Test fim_render with the response
    let l:fim_data = {}
    if !empty(l:result)
        let l:response_json = s:build_response(l:result)
        let l:fim_data = llama#fim_render_test(a:col_num, a:line_num, l:response_json)
    endif

    " Build result object
    let l:result_obj = {
        \ 'file': a:file_path,
        \ 'line': a:line_num,
        \ 'column': a:col_num,
        \ 'test_id': a:test_id,
        \ 'request': {
        \     'input_prefix': l:prefix,
        \     'prompt': l:middle,
        \     'input_suffix': l:suffix,
        \     'n_indent': l:indent
        \ },
        \ 'response': l:response_obj,
        \ 'final_suggestion': l:result,
        \ 'fim_render': {
        \     'pos_x': get(l:fim_data, 'pos_x', 0),
        \     'pos_y': get(l:fim_data, 'pos_y', 0),
        \     'line_cur': get(l:fim_data, 'line_cur', ''),
        \     'can_accept': get(l:fim_data, 'can_accept', v:false),
        \     'content': get(l:fim_data, 'content', [])
        \ }
        \ }

    call add(s:results, l:result_obj)

    echo 'Tested: ' . a:test_id
endfunction

" Helper to test fim_render with specific content
function! s:test_fim_render_case(file_path, line_num, col_num, test_id, content)
    execute 'edit ' . a:file_path
    call cursor(a:line_num, a:col_num + 1)

    let l:response_json = s:build_response(a:content)
    let l:fim_data = llama#fim_render_test(a:col_num, a:line_num, l:response_json)

    let l:result_obj = {
        \ 'file': a:file_path,
        \ 'line': a:line_num,
        \ 'column': a:col_num,
        \ 'test_id': a:test_id,
        \ 'input_content': a:content,
        \ 'fim_render': {
        \     'pos_x': get(l:fim_data, 'pos_x', 0),
        \     'pos_y': get(l:fim_data, 'pos_y', 0),
        \     'line_cur': get(l:fim_data, 'line_cur', ''),
        \     'can_accept': get(l:fim_data, 'can_accept', v:false),
        \     'content': get(l:fim_data, 'content', [])
        \ }
        \ }

    call add(s:results, l:result_obj)
    echo 'Tested: ' . a:test_id
endfunction

" Test fim_render deduplication logic with specific edge cases
function! s:test_fim_render_deduplication()
    " Test 1: Empty first line, repeating next lines (line 959-962 in llama.vim)
    call s:test_fim_render_case(
        \ 'src/test/testData/completion/large.ts',
        \ 10, 0,
        \ 'fim_render_dedup::empty_first_repeating',
        \ "\nfunction greeting(): string {"
        \ )

    " Test 2: Suggestion repeats suffix (line 964-967)
    call s:test_fim_render_case(
        \ 'src/test/testData/completion/large.ts',
        \ 10, 9,
        \ 'fim_render_dedup::repeats_suffix',
        \ "greeting(): string {"
        \ )

    " Test 3: Normal multi-line suggestion (baseline)
    call s:test_fim_render_case(
        \ 'src/test/testData/completion/large.ts',
        \ 50, 0,
        \ 'fim_render_dedup::normal_multiline',
        \ "const result = {\n    value: 42,\n    valid: true\n};"
        \ )

    " Test 4: Whitespace-only content (line 1004-1006)
    call s:test_fim_render_case(
        \ 'src/test/testData/completion/large.ts',
        \ 25, 10,
        \ 'fim_render_dedup::whitespace_only',
        \ "   \n  \n   "
        \ )

    " Test 5: Trailing newlines removal (line 903-905)
    call s:test_fim_render_case(
        \ 'src/test/testData/completion/large.ts',
        \ 100, 15,
        \ 'fim_render_dedup::trailing_newlines',
        \ "console.log('test');\n\n\n"
        \ )
endfunction

function! s:run_all_tests()
    " Test 1: Empty file
    call s:test_position('src/test/testData/completion/empty.ts', 1, 0, 'empty.ts::line_1_col_0')

    " Test 2-11: Large file at various positions
    " Line 1 (greeting function start)
    call s:test_position('src/test/testData/completion/large.ts', 1, 10, 'large.ts::line_1_col_10')

    " Line 10 (simple function)
    call s:test_position('src/test/testData/completion/large.ts', 10, 0, 'large.ts::line_10_col_0')
    call s:test_position('src/test/testData/completion/large.ts', 10, 5, 'large.ts::line_10_col_5')

    " Line 50 (inside loop)
    call s:test_position('src/test/testData/completion/large.ts', 50, 20, 'large.ts::line_50_col_20')
    call s:test_position('src/test/testData/completion/large.ts', 50, 0, 'large.ts::line_50_col_0')

    " Line 100 (deeper nesting)
    call s:test_position('src/test/testData/completion/large.ts', 100, 15, 'large.ts::line_100_col_15')

    " Line 300 (middle of file - prefix excludes line 1, suffix excludes end)
    " With n_prefix=256: prefix starts at line 300-256=44 (doesn't include line 1)
    " With n_suffix=64: suffix ends at line 300+64=364 (doesn't include last line 455)
    call s:test_position('src/test/testData/completion/large.ts', 300, 10, 'large.ts::line_300_col_10')

    " Line 350 (another middle position)
    " Prefix starts at line 350-256=94 (doesn't include line 1)
    " Suffix ends at line 350+64=414 (doesn't include line 455)
    call s:test_position('src/test/testData/completion/large.ts', 350, 20, 'large.ts::line_350_col_20')

    " Last line (end of file)
    let l:max_line = line('$')
    call s:test_position('src/test/testData/completion/large.ts', l:max_line, 0, 'large.ts::line_' . l:max_line . '_col_0')

    " New: Add fim_render-specific deduplication tests
    call s:test_fim_render_deduplication()

    " Write results to file
    call writefile([json_encode(s:results)], s:output_file)

    echo 'Results written to: ' . s:output_file
endfunction

" Run all tests
call s:run_all_tests()

" Exit Vim
quit!
