import React from "react";
import { Banknote } from "lucide-react";

const MoneyInput = ({
  value,
  onChange,
  label = "Amount",
  placeholder = "0.00",
  id = "amount",
}) => {
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-medium text-gray-700">
        {label}
      </label>
      <div className="relative">
        <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
          <span className="text-gray-500 sm:text-sm">$</span>
        </div>
        <input
          type="number"
          step="0.01"
          min="0"
          id={id}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="block w-full pl-7 pr-12 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500 sm:text-sm"
          placeholder={placeholder}
        />
        <div className="absolute inset-y-0 right-0 pr-3 flex items-center pointer-events-none">
          <Banknote className="h-4 w-4 text-gray-400" />
        </div>
      </div>
      <p className="text-xs text-gray-500">
        Amount will be processed in cents.
      </p>
    </div>
  );
};

export default MoneyInput;
