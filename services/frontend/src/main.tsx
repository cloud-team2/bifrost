import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import { AppProvider } from './store/AppStore'
import { ToastProvider } from './components/Toast'
import './styles/index.css'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <AppProvider>
      <ToastProvider>
        <App />
      </ToastProvider>
    </AppProvider>
  </React.StrictMode>,
)
