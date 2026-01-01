import React, { useState } from "react";
import Navbar from "./components/ui/Navbar";
import Footer from "./components/ui/Footer";
import CreateUserForm from "./components/CreateUserForm";
import UserSearch from "./components/UserSearch";
import TransferMoney from "./components/TransferMoney";

function App() {
  const [activeView, setActiveView] = useState("transfer");

  // Logic to determine which component to mount
  const renderView = () => {
    switch (activeView) {
      case "create":
        return <CreateUserForm />;
      case "search":
        return <UserSearch />;
      case "transfer":
        return <TransferMoney />;
      default:
        return <TransferMoney />;
    }
  };

  return (
    <div className="min-h-screen flex flex-col bg-gray-50 text-gray-900">
      <Navbar activeView={activeView} setActiveView={setActiveView} />

      <main className="grow container mx-auto px-4 py-12">
        <div className="animate-in fade-in slide-in-from-bottom-4 duration-500">
          {renderView()}
        </div>
      </main>

      <Footer />
    </div>
  );
}

export default App;
