import React from "react";
import { UserPlus, Search, Send, Landmark } from "lucide-react";

const Navbar = ({ activeView, setActiveView }) => {
  const navItems = [
    { id: "create", label: "Register User", icon: UserPlus },
    { id: "search", label: "User Lookup", icon: Search },
    { id: "transfer", label: "Send Money", icon: Send },
  ];

  return (
    <nav className="bg-white border-b border-gray-200 sticky top-0 z-50">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="flex justify-between h-16 items-center">
          <div
            className="flex items-center gap-2 cursor-pointer"
            onClick={() => setActiveView("create")}
          >
            <Landmark className="h-8 w-8 text-blue-600" />
            <span className="text-xl font-bold bg-clip-text text-transparent bg-linear-to-r from-blue-600 to-indigo-600">
              PaySys
            </span>
          </div>

          <div className="flex space-x-4">
            {navItems.map((item) => (
              <button
                key={item.id}
                onClick={() => setActiveView(item.id)}
                className={`flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors
                  ${
                    activeView === item.id
                      ? "bg-blue-50 text-blue-600"
                      : "text-gray-600 hover:bg-gray-50 hover:text-blue-600"
                  }`}
              >
                <item.icon className="w-4 h-4" />
                {item.label}
              </button>
            ))}
          </div>
        </div>
      </div>
    </nav>
  );
};

export default Navbar;
