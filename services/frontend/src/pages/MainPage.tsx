function MainPage() {
  return (
    <div className="min-h-screen flex flex-col">
      <header className="bg-white border-b px-6 py-4 flex items-center justify-between">
        <h1 className="text-xl font-bold">Data Orchestration Platform</h1>
        {/* TODO: 사용자 메뉴, 로그아웃 */}
      </header>
      <main className="flex-1 flex">
        <aside className="w-64 bg-gray-50 border-r p-4">
          {/* TODO: 사이드바 (Datasource 등록, Pipeline 생성 버튼) */}
        </aside>
        <section className="flex-1 bg-white">
          {/* TODO: React Flow 캔버스 */}
          <div className="h-full flex items-center justify-center text-gray-400">
            캔버스 영역 (React Flow 적용 예정)
          </div>
        </section>
        <aside className="w-80 bg-gray-50 border-l p-4">
          {/* TODO: 상세 패널 / 채팅 패널 */}
        </aside>
      </main>
    </div>
  )
}

export default MainPage
