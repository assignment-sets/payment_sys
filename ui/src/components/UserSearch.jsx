import React, { useState } from "react";
import axios from "axios";
import { toDollars } from "../utils/moneyUtils";
import Button from "./ui/Button";
import ErrorDisplay from "./ui/ErrorDisplay";
import { Search, User as UserIcon, Wallet } from "lucide-react";

const UserSearch = () => {
  const [userId, setUserId] = useState("");
  const [userData, setUserData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [serverError, setServerError] = useState({ message: "", status: null });

  const handleSearch = async (e) => {
    e.preventDefault();
    if (!userId) return;

    setLoading(true);
    setServerError({ message: "", status: null });
    setUserData(null);

    try {
      const response = await axios.get(
        `http://localhost:8080/api/users/${userId}`
      );
      setUserData(response.data);
    } catch (err) {
      setServerError({
        message: err.response?.data?.error || "Search failed",
        status: err.response?.status,
      });
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="max-w-2xl mx-auto p-6">
      <form onSubmit={handleSearch} className="flex gap-2 mb-8">
        <input
          type="number"
          value={userId}
          onChange={(e) => setUserId(e.target.value)}
          placeholder="Enter User ID (e.g. 1)"
          className="flex-1 px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 outline-none"
        />
        <Button
          title="Search"
          type="submit"
          isLoading={loading}
          variant="secondary"
        >
          <Search className="w-4 h-4" />
        </Button>
      </form>

      {/* Standardized Error View */}
      <ErrorDisplay message={serverError.message} status={serverError.status} />

      {userData && (
        <div className="bg-white border border-gray-200 rounded-xl p-6 shadow-sm animate-in fade-in slide-in-from-bottom-2">
          <div className="flex items-center justify-between border-b pb-4 mb-4">
            <div className="flex items-center gap-3">
              <div className="p-3 bg-blue-100 rounded-full">
                <UserIcon className="text-blue-600" />
              </div>
              <div>
                <h3 className="text-lg font-bold">{userData.name}</h3>
                <p className="text-gray-500 text-sm">{userData.email}</p>
              </div>
            </div>
            <span className="text-xs font-mono bg-gray-100 px-2 py-1 rounded">
              UID: {userData.id}
            </span>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div className="p-4 bg-green-50 rounded-lg">
              <p className="text-sm text-green-600 font-medium flex items-center gap-1">
                <Wallet className="w-4 h-4" /> Current Balance
              </p>
              <p className="text-2xl font-bold text-green-700">
                ${toDollars(userData.balance)}
              </p>
            </div>
            <div className="p-4 bg-gray-50 rounded-lg">
              <p className="text-sm text-gray-500 font-medium">Account ID</p>
              <p className="text-2xl font-bold text-gray-700">
                #{userData.accountId}
              </p>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default UserSearch;
