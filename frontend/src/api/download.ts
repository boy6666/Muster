import { http } from './http'

/** 带 Authorization 的文件下载（模板/导出/归档）。 */
export async function downloadFile(url: string, filename: string,
                                   method: 'GET' | 'POST' = 'GET'): Promise<void> {
  const resp = await http.request<Blob>({ url, method, responseType: 'blob' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(resp.data)
  link.download = filename
  link.click()
  URL.revokeObjectURL(link.href)
}
