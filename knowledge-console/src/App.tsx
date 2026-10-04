import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useAuthStore } from './store/auth';
import MainLayout from './components/MainLayout';
import LoginPage from './pages/LoginPage';
import TenantsPage from './pages/TenantsPage';
import ModelConfigPage from './pages/ModelConfigPage';
import PromptPage from './pages/PromptPage';
import KnowledgeBasesPage from './pages/KnowledgeBasesPage';
import KbDetailPage from './pages/KbDetailPage';
import ToolConfigPage from './pages/ToolConfigPage';
import QuotaPage from './pages/QuotaPage';
import ChatPage from './pages/ChatPage';
import ImageStudioPage from './pages/ImageStudioPage';
import SubtitlePage from './pages/SubtitlePage';
import FileLibraryPage from './pages/FileLibraryPage';
import TranslateLangPage from './pages/TranslateLangPage';
import UsersPage from './pages/UsersPage';

function RequireAuth({ children }: { children: ReactNode }) {
  const token = useAuthStore((s) => s.token);
  if (!token) {
    return <Navigate to="/login" replace />;
  }
  return children;
}

/** 按角色限制路由 */
function RequireUserType({ types, children }: { types: number[]; children: ReactNode }) {
  const user = useAuthStore((s) => s.user);
  if (!user || !types.includes(user.userType)) {
    return <Navigate to="/" replace />;
  }
  return children;
}

/** 普通用户功能菜单授权校验（管理员不限制）。 */
function RequireMenu({ code, children }: { code: string; children: ReactNode }) {
  const user = useAuthStore((s) => s.user);
  if (user?.userType === 2 && !user.menuCodes.includes(code)) {
    return <Navigate to="/" replace />;
  }
  return children;
}

/** 登录后按角色落地 */
function HomeRedirect() {
  const user = useAuthStore((s) => s.user);
  if (user?.userType === 0) {
    return <Navigate to="/tenants" replace />;
  }
  if (user?.userType === 2) {
    if (user.menuCodes.includes('chat')) {
      return <Navigate to="/chat" replace />;
    }
    if (user.menuCodes.includes('image-studio')) {
      return <Navigate to="/image-studio" replace />;
    }
  }
  return <Navigate to="/chat" replace />;
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route
          path="/"
          element={
            <RequireAuth>
              <MainLayout />
            </RequireAuth>
          }
        >
          <Route index element={<HomeRedirect />} />
          <Route
            path="tenants"
            element={
              <RequireUserType types={[0]}>
                <TenantsPage />
              </RequireUserType>
            }
          />
          <Route
            path="model-config"
            element={
              <RequireUserType types={[1]}>
                <ModelConfigPage />
              </RequireUserType>
            }
          />
          <Route
            path="knowledge-bases"
            element={
              <RequireUserType types={[1]}>
                <KnowledgeBasesPage />
              </RequireUserType>
            }
          />
          <Route
            path="knowledge-bases/:id"
            element={
              <RequireUserType types={[1]}>
                <KbDetailPage />
              </RequireUserType>
            }
          />
          <Route
            path="prompts"
            element={
              <RequireUserType types={[1]}>
                <PromptPage />
              </RequireUserType>
            }
          />
          <Route
            path="tool-config"
            element={
              <RequireUserType types={[1]}>
                <ToolConfigPage />
              </RequireUserType>
            }
          />
          <Route
            path="quotas"
            element={
              <RequireUserType types={[1]}>
                <QuotaPage />
              </RequireUserType>
            }
          />
          <Route
            path="users"
            element={
              <RequireUserType types={[1]}>
                <UsersPage />
              </RequireUserType>
            }
          />
          <Route
            path="chat"
            element={
              <RequireMenu code="chat">
                <ChatPage />
              </RequireMenu>
            }
          />
          <Route
            path="image-studio"
            element={
              <RequireMenu code="image-studio">
                <ImageStudioPage />
              </RequireMenu>
            }
          />
          <Route
            path="subtitle"
            element={
              <RequireMenu code="subtitle">
                <SubtitlePage />
              </RequireMenu>
            }
          />
          <Route
            path="library"
            element={
              <RequireMenu code="library">
                <FileLibraryPage />
              </RequireMenu>
            }
          />
          <Route
            path="translate-langs"
            element={
              <RequireMenu code="translate-lang">
                <TranslateLangPage />
              </RequireMenu>
            }
          />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
